package com.chaeyeongmin.payment_sim.recovery.ev01;

import com.chaeyeongmin.payment_sim.PaymentSimApplication;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryCandidateRepository;
import org.springframework.boot.convert.DurationStyle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.step.AbstractStep;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.sql.DriverManager;

/**
 * opt-in한 전용 localhost DB에서 기존 recoveryJob을 한 번 실행하는 test 전용 main.
 *
 * <p>Python 하네스가 전달한 DB/출력 경로/실행 ID를 사용한다. 준비 모드에서는 schema만 초기화하고,
 * 일반 모드에서는 실제 Discovery/Process Step을 수행한다. Recovery Task를 직접 완료시키지 않는다.
 * JDBC/Boot datasource 검사는 fixture를 만든 DB와 Job이 처리할 DB가 달라지는 것을 막기 위한 경계다.
 *
 * <p>실행 환경과 launcher 사용법은 EV-01 README에 둔다. Job/Step의 관찰 결과를 기록하는 역할이며
 * listener를 붙였다는 사실만으로 모든 claim/lease의 중간 전이를 관찰했다고 해석하지 않는다.
 */
public class Ev01RecoveryLauncher {
    /**
     * 환경변수로 지정된 C DB를 확인하고 schema 준비 또는 Job 1회 실행을 수행한다.
     *
     * @param args 현재 직접 해석하지 않는다. 입력은 하네스가 전달하는 환경변수다.
     * @throws Exception 접속 확인, Spring 초기화, Job 실행 또는 evidence 저장이 실패한 경우
     */
    public static void main(String[] args) throws Exception {
        if (!"YES".equals(System.getenv("EV01_C_ALLOW_WRITE"))) throw new IllegalStateException("C opt-in required");
        if (!"jdbc:postgresql://localhost:5432/payment_sim_ev01_c".equals(System.getenv("SPRING_DATASOURCE_URL")))
            throw new IllegalStateException("Only dedicated localhost C DB is allowed");
        Path output = Path.of(System.getenv("EV01_C_JOB_OUTPUT"));
        Files.writeString(Path.of(output + ".pid"), Long.toString(ProcessHandle.current().pid()));
        Map<String,Object> result = new LinkedHashMap<>();
        // Boot schema 초기화 이전에 실제 JDBC 접속 DB를 확인한다.
        try (var connection = DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),
                System.getenv("SPRING_DATASOURCE_USERNAME"), System.getenv("SPRING_DATASOURCE_PASSWORD"));
             var statement = connection.createStatement();
             var identity = statement.executeQuery("SELECT current_database(), inet_server_port()")) {
            if (!identity.next() || !"payment_sim_ev01_c".equals(identity.getString(1)) || identity.getInt(2)!=5432)
                throw new IllegalStateException("Actual JDBC C DB mismatch");
            result.put("jdbcDatabase",identity.getString(1));
        }
        boolean prepare = "YES".equals(System.getenv("EV01_C_PREPARE_ONLY"));
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PaymentSimApplication.class)
                .web(WebApplicationType.NONE).run("--spring.profiles.active=postgres", "--spring.sql.init.mode="+(prepare?"always":"never"),
                        "--spring.batch.jdbc.initialize-schema="+(prepare?"always":"never"), "--spring.batch.job.enabled=false",
                        "--spring.datasource.url="+System.getenv("SPRING_DATASOURCE_URL"),
                        "--spring.datasource.username="+System.getenv("SPRING_DATASOURCE_USERNAME"),
                        "--spring.datasource.password="+System.getenv("SPRING_DATASOURCE_PASSWORD"),
                        "--spring.datasource.hikari.connection-init-sql=DO $$ BEGIN IF current_database() <> 'payment_sim_ev01_c' THEN RAISE EXCEPTION 'C DB mismatch'; END IF; END $$;",
                        "--payment.van.tcp.host=localhost", "--payment.van.tcp.port=9090",
                        "--payment.recovery.schedule.enabled=false", "--payment.van.mode=tcp")) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            if (!"payment_sim_ev01_c".equals(jdbc.queryForObject("SELECT current_database()",String.class)))
                throw new IllegalStateException("Spring datasource differs from fixture database");
            result.put("pid", ProcessHandle.current().pid());
            if (prepare) { result.put("status","SCHEMA_PREPARED"); write(output,result); return; }
            var candidates = context.getBean(RecoveryCandidateRepository.class);
            LocalDateTime now = LocalDateTime.now();
            var env = context.getEnvironment();
            result.put("approvalCandidates", candidates.findApprovalCandidates(
                    now.minus(DurationStyle.detectAndParse(env.getRequiredProperty("payment.recovery.discovery.unknown-timeout-age"))),
                    now.minus(DurationStyle.detectAndParse(env.getRequiredProperty("payment.recovery.discovery.stale-processing-age")))));
            result.put("cancelCandidates", candidates.findCancelCandidates(
                    now.minus(DurationStyle.detectAndParse(env.getRequiredProperty("payment.recovery.discovery.unknown-timeout-age"))),
                    now.minus(DurationStyle.detectAndParse(env.getRequiredProperty("payment.recovery.discovery.stale-pending-age")))));
            result.put("reversalCandidates", candidates.findReversalCandidates(
                    now.minus(DurationStyle.detectAndParse(env.getRequiredProperty("payment.recovery.discovery.stale-pending-age")))));
            write(output,result);
            // Step 경계의 PENDING/최종 Task를 보관한다. Worker/claim/lease 정책은 교체하지 않는다.
            for (String name : List.of("discoverAndRegisterRecoveryTasksStep", "processRecoveryTasksStep")) {
                ((AbstractStep) context.getBean(name)).registerStepExecutionListener(new StepExecutionListener() {
                    @Override public ExitStatus afterStep(StepExecution step) {
                        result.put(name + "Tasks", jdbc.queryForList("SELECT * FROM payment_recovery_task ORDER BY id"));
                        write(output,result); return null;
                    }
                });
            }
            JobParameters params = new JobParametersBuilder().addString("ev01.run", System.getenv("EV01_C_RUN_ID"))
                    // 본 실행과 재실행 검증은 같은 fixture를 보되 별도 JobInstance로 구분한다.
                    .addString("ev01.invocation", UUID.randomUUID().toString()).toJobParameters();
            JobExecution execution = context.getBean(JobLauncher.class).run(context.getBean("recoveryJob",Job.class),params);
            result.put("jobExecutionId",execution.getId()); result.put("jobInstanceId",execution.getJobInstance().getId());
            result.put("status",execution.getStatus().name());
            result.put("start",String.valueOf(execution.getStartTime())); result.put("end",String.valueOf(execution.getEndTime()));
            result.put("durationMs",execution.getEndTime()==null ? null : Duration.between(execution.getStartTime(),execution.getEndTime()).toMillis());
            List<Map<String,Object>> steps = new ArrayList<>();
            for (StepExecution step : execution.getStepExecutions()) {
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("id",step.getId()); row.put("name",step.getStepName()); row.put("status",step.getStatus().name());
                row.put("start",String.valueOf(step.getStartTime())); row.put("end",String.valueOf(step.getEndTime()));
                row.put("durationMs",step.getEndTime()==null ? null : Duration.between(step.getStartTime(),step.getEndTime()).toMillis());
                steps.add(row);
            }
            result.put("steps",steps); write(output,result);
            if (execution.getStatus()!=BatchStatus.COMPLETED || steps.size()!=2) throw new IllegalStateException("Job incomplete");
        } catch (Exception exception) {
            result.put("launcherFailure",exception.getClass().getSimpleName()); write(output,result); throw exception;
        }
    }
    /**
     * 진행 중인 evidence를 같은 invocation의 JSON 파일에 저장한다.
     *
     * <p>Step 경계마다 같은 파일을 갱신하므로 append 로그가 아니다. 증거 저장 실패를 조용히 무시하면
     * 실행 성공만 남을 수 있어 예외로 전달한다. 원본 측정과 다른 invocation의 경로를 공유하면 안 된다.
     *
     * @param output 이번 invocation의 결과 경로
     * @param result 지금까지 수집된 Job/Step/Task 관찰 정보
     */
    private static void write(Path output, Map<String,Object> result) {
        try { new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),result); }
        catch (Exception exception) { throw new IllegalStateException("Cannot persist evidence",exception); }
    }
}
