package com.chaeyeongmin.payment_sim.recovery.ev01;

import com.chaeyeongmin.payment_sim.PaymentSimApplication;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryCandidateRepository;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import com.chaeyeongmin.payment_sim.van.client.dto.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.*;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import java.time.*;

/** Test-only, explicit invocation; no fixtures, schema initialization or retry overrides. */
public final class B13RecoveryLauncher {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
    static Map<String,String> targets(JsonNode rows) {
        Map<String,String> result=new TreeMap<>(); int approved=0;
        for (JsonNode row: rows) {
            require(row.path("attemptSeq").asInt()==1,"attempt mismatch");
            String outcome=row.path("outcome").asText();
            require(Set.of("APPROVED","NOT_FOUND").contains(outcome),"outcome mismatch");
            require(result.put(row.path("posTrx").asText(),outcome)==null,"duplicate target");
            if (outcome.equals("APPROVED")) approved++;
        }
        require(result.size()==13 && approved==11,"13/11/2 required");
        Set<String> fixed=Set.of("2416-0007","2428-0007","2447-0007","2459-0007","2463-0006","2533-0007","2535-0007","2537-0007","2544-0007","2565-0007","2571-0007","2574-0007","2575-0007");
        require(result.keySet().equals(fixed.stream().map(k->"2376-20261029-"+k).collect(java.util.stream.Collectors.toSet())),"fixed B13 set mismatch");
        require("NOT_FOUND".equals(result.get("2376-20261029-2447-0007")) && "NOT_FOUND".equals(result.get("2376-20261029-2544-0007")),"fixed B13 outcome mismatch");
        return result;
    }
    static void topology(Path config) throws Exception {
        require(new ProcessBuilder("python3","engineering-validation/ev01/b13/isolation.py","--check",config.toString()).inheritIO().start().waitFor()==0,"live Docker boundary mismatch");
    }
    static VanGateway inquiryOnly(InvocationHandler inquiry) {
        return (VanGateway) Proxy.newProxyInstance(VanGateway.class.getClassLoader(),new Class[]{VanGateway.class},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class) {
                return switch(method.getName()) {case "toString" -> "B13 Inquiry-only gateway"; case "hashCode" -> System.identityHashCode(proxy); case "equals" -> proxy==args[0]; default -> throw new IllegalStateException("Object method");};
            }
            require(method.getName().equals("inquiry"),"non-Inquiry VAN call blocked");
            return inquiry.invoke(proxy,method,args);
        });
    }
    static String connectionGuard(JsonNode cfg) {
        JsonNode id=cfg.get("identity");
        String condition="current_database()="+literal(id.path("database").asText())+" AND current_user="+literal(id.path("user").asText())+
            " AND inet_server_port()="+id.path("port").asInt()+" AND host(inet_server_addr())="+literal(id.path("address").asText())+" AND current_setting('data_directory')="+literal(id.path("directory").asText())+
            " AND (SELECT system_identifier::text FROM pg_control_system())="+literal(id.path("system").asText());
        return "DO $$ BEGIN IF NOT ("+condition+") THEN RAISE EXCEPTION 'B13 backend mismatch'; END IF; END $$";
    }
    static String literal(String value) {return "'"+value.replace("'","''")+"'";}
    static String identitySql() {
        return "SELECT json_build_object('database',current_database(),'system',system_identifier::text,'directory',current_setting('data_directory'),'address',host(inet_server_addr()),'port',inet_server_port(),'user',current_user)::text FROM pg_control_system()";
    }
    static void identity(JdbcTemplate jdbc, JsonNode cfg) throws Exception {
        require(JSON.readTree(jdbc.queryForObject(identitySql(),String.class)).equals(cfg.get("identity")),"JDBC backend identity mismatch");
        require(jdbc.queryForObject("SHOW server_version_num",Integer.class)/10000==17,"PostgreSQL 17 required");
    }
    static JdbcTemplate connect(JsonNode cfg, String password) throws Exception {
        String url=cfg.path("url").asText();
        require(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/[a-z0-9_]+"),"literal URL without options required");
        require(!Set.of(5432,5433).contains(Integer.parseInt(url.split(":")[3].split("/")[0])),"original/default port forbidden");
        var ds=new SingleConnectionDataSource(DriverManager.getConnection(url,cfg.path("user").asText(),password),true);
        JdbcTemplate jdbc=new JdbcTemplate(ds); identity(jdbc,cfg); return jdbc;
    }
    static List<Map<String,Object>> attempts(JdbcTemplate j) {
        return j.queryForList("SELECT pos_trx,attempt_seq,amount,final_status,approval_no,van_trx_id,updated_at FROM payment_attempt ORDER BY pos_trx,attempt_seq");
    }
    static List<Map<String,Object>> tasks(JdbcTemplate j) { return j.queryForList("SELECT * FROM payment_recovery_task ORDER BY id"); }
    static List<Map<String,Object>> history(JdbcTemplate j) { return j.queryForList("SELECT * FROM payment_recovery_history ORDER BY id"); }
    static List<Map<String,Object>> vanRows(JdbcTemplate j) { return j.queryForList("SELECT * FROM van_approval ORDER BY pos_trx,attempt_seq"); }
    static Map<String,Object> vanLedger(JdbcTemplate j) {return Map.of("approval",vanRows(j),"cancel",j.queryForList("SELECT * FROM van_cancel ORDER BY cancel_pos_trx"),"reversal",j.queryForList("SELECT * FROM van_reversal ORDER BY reversal_pos_trx"));}
    static Map<String,Object> find(List<Map<String,Object>> rows,String key,String value) {
        var found=rows.stream().filter(r->value.equals(r.get(key))).toList(); require(found.size()==1,"row identity/duplicate mismatch: "+value); return found.getFirst();
    }
    static void write(Path path, Object value) throws Exception { JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),value); }
    static void backend(JdbcTemplate van, JsonNode cfg) {
        var rows=van.queryForList("SELECT datname,host(client_addr) AS client_addr,usename FROM pg_stat_activity WHERE application_name=?",cfg.path("tcp").path("applicationName").asText());
        require(!rows.isEmpty() && rows.stream().allMatch(r->cfg.path("van").path("identity").path("database").asText().equals(r.get("datname")) && cfg.path("tcp").path("address").asText().equals(r.get("client_addr")) && cfg.path("tcp").path("dbUser").asText().equals(r.get("usename"))),"VAN backend proof missing/mismatched");
    }
    public static void main(String[] args) throws Exception {
        require("YES".equals(System.getenv("B13_ALLOW_WRITE")),"B13 opt-in required");
        Path cfgPath=Path.of(System.getenv("B13_CONFIG")); JsonNode cfg=JSON.readTree(cfgPath.toFile());
        Path out=cfgPath.toAbsolutePath().getParent();
        require(cfg.path("payment").path("url").asText().equals("jdbc:postgresql://127.0.0.1:15432/payment_sim"),"B13 Payment endpoint mismatch");
        require(cfg.path("van").path("url").asText().equals("jdbc:postgresql://127.0.0.1:15433/van_sim"),"B13 VAN endpoint mismatch");
        require(cfg.path("tcp").path("port").asInt()==19090,"B13 TCP endpoint mismatch");
        require(Files.exists(out.resolve("docker-before.json")),"Docker attestation required");
        require(!Files.exists(out.resolve("pre.json")),"output reuse forbidden");
        // Atomic, persistent one-shot reservation: never remove/reuse a failed invocation directory.
        Files.createFile(out.resolve("invocation.claim"));
        var expected=targets(JSON.readTree(Path.of("engineering-validation/ev01/b13/targets.json").toFile()));
        topology(cfgPath);
        JdbcTemplate payment=connect(cfg.get("payment"),System.getenv("B13_PAYMENT_PASSWORD"));
        require(Boolean.TRUE.equals(payment.queryForObject("SELECT pg_try_advisory_lock(130113,13)",Boolean.class)),"another B13 launcher owns this database");
        JdbcTemplate van=connect(cfg.get("van"),System.getenv("B13_VAN_PASSWORD"));
        backend(van,cfg);
        var beforeVan=vanRows(van); var beforeLedger=vanLedger(van); var before=attempts(payment);
        for (var entry:expected.entrySet()) {
            var a=find(before,"pos_trx",entry.getKey());
            require(a.get("final_status")==null && a.get("approval_no")==null && a.get("van_trx_id")==null && ((Number)a.get("amount")).longValue()==10000 && ((Number)a.get("attempt_seq")).intValue()==1,"unexpected initial attempt");
            var v=beforeVan.stream().filter(r->entry.getKey().equals(r.get("pos_trx"))).toList();
            require(entry.getValue().equals("NOT_FOUND") ? v.isEmpty() : v.size()==1 && ((Number)v.getFirst().get("attempt_seq")).intValue()==1 && "APPROVED".equals(v.getFirst().get("approval_status")) && ((Number)v.getFirst().get("amount")).longValue()==10000 && v.getFirst().get("approval_no")!=null && v.getFirst().get("van_trx_id")!=null,"VAN expectation mismatch");
        }
        for (String table:List.of("payment_recovery_task","payment_recovery_history","batch_job_execution","batch_job_instance"))
            require(payment.queryForObject("SELECT count(*) FROM "+table,Long.class)==0,"existing recovery/batch state");
        for(String excluded:List.of("2376-20261029-2446-0008","2376-20261029-2580-0007")) {
            require(before.stream().noneMatch(r->excluded.equals(r.get("pos_trx"))) && beforeVan.stream().noneMatch(r->excluded.equals(r.get("pos_trx"))),"excluded transaction unexpectedly present");
        }
        for(String key:expected.keySet()) {
            require(payment.queryForObject("SELECT count(*) FROM payment_external_info WHERE pos_trx=? AND attempt_seq=1",Integer.class,key)==1,"external info mismatch");
            require(payment.queryForObject("SELECT last_seq FROM payment_attempt_seq WHERE pos_trx=?",Integer.class,key)==1,"attempt sequence mismatch");
        }
        write(out.resolve("pre.json"),Map.of("attempts",before,"van",beforeVan,"vanLedger",beforeLedger,"tasks",tasks(payment),"history",history(payment)));
        // Detect wall-clock discontinuity/suspend during execution; fail closed without later finalization.
        Thread watch=new Thread(()->{long wall=System.currentTimeMillis(), mono=System.nanoTime(); while(true) { try {Thread.sleep(1000);}catch(InterruptedException e){return;} long w=System.currentTimeMillis(),n=System.nanoTime(); if(w-wall>10000 || w<wall || (n-mono)>10_000_000_000L) {System.err.println("B13 clock gap: halted; inspect partial evidence");Runtime.getRuntime().halt(70);}wall=w;mono=n;}},"b13-clock-gap");
        watch.setDaemon(true);watch.start();
        List<Object> inquiries=new ArrayList<>();
        try (var context=new SpringApplicationBuilder(PaymentSimApplication.class).web(WebApplicationType.NONE)
            .initializers(c->c.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor(){
                @Override public Object postProcessAfterInitialization(Object bean,String name) {
                    if (!(bean instanceof VanGateway)) return bean;
                    return inquiryOnly((proxy,method,a)->{
                        if (method.getDeclaringClass()==Object.class) return method.invoke(bean,a);
                        require(method.getName().equals("inquiry"),"non-Inquiry VAN call blocked");
                        var request=(VanInquiryRequest)a[0];
                        require(request.targetType()==VanInquiryTargetType.APPROVAL && request.attemptSeq()==1 && expected.containsKey(request.posTrx()),"foreign Inquiry blocked");
                        topology(cfgPath); identity(van,cfg.get("van")); backend(van,cfg);
                        VanInquiryResponse response;
                        try {response=(VanInquiryResponse)method.invoke(bean,a);}catch(InvocationTargetException e){throw e.getCause();}
                        inquiries.add(Map.of("request",request,"response",response)); write(out.resolve("inquiries.json"),inquiries);
                        require(response.targetType()==request.targetType() && request.targetTrxNo().equals(response.targetTrxNo()) && Objects.equals(request.targetAttemptSeq(),response.targetAttemptSeq()),"Inquiry identity mismatch");
                        if (expected.get(request.posTrx()).equals("NOT_FOUND")) require(response.resultCode()==VanInquiryResultCode.NOT_FOUND,"NOT_FOUND expected");
                        else {var row=find(beforeVan,"pos_trx",request.posTrx()); require(response.resultCode()==VanInquiryResultCode.SUCCESS && response.status().name().equals("APPROVED") && row.get("approval_no").equals(response.approvalNo()) && row.get("van_trx_id").equals(response.vanTrxId()),"Inquiry ledger mismatch");}
                        topology(cfgPath);
                        require(beforeLedger.equals(vanLedger(van)),"VAN ledger changed"); return response;
                    });
                }
            })).run("--spring.profiles.active=postgres","--spring.sql.init.mode=never","--spring.batch.jdbc.initialize-schema=never","--spring.batch.job.enabled=false","--payment.recovery.schedule.enabled=false",
                "--spring.datasource.url="+cfg.path("payment").path("url").asText(),"--spring.datasource.username="+cfg.path("payment").path("user").asText(),"--spring.datasource.password="+System.getenv("B13_PAYMENT_PASSWORD"),
                "--spring.datasource.hikari.maximum-pool-size=1","--spring.datasource.hikari.connection-init-sql="+connectionGuard(cfg.get("payment")),"--payment.van.mode=tcp","--payment.van.tcp.host=127.0.0.1","--payment.van.tcp.port="+cfg.path("tcp").path("port").asInt())) {
            JdbcTemplate spring=context.getBean(JdbcTemplate.class); identity(spring,cfg.get("payment"));
            write(out.resolve("jdbc-identities.json"),Map.of("paymentDirect",JSON.readTree(payment.queryForObject(identitySql(),String.class)),"paymentSpring",JSON.readTree(spring.queryForObject(identitySql(),String.class)),"vanDirect",JSON.readTree(van.queryForObject(identitySql(),String.class)),"vanBackends",van.queryForList("SELECT datname,host(client_addr) AS client_addr,application_name,pid,state FROM pg_stat_activity WHERE application_name=?",cfg.path("tcp").path("applicationName").asText())));
            var repo=context.getBean(RecoveryCandidateRepository.class); var now=LocalDateTime.now(context.getBean(java.time.Clock.class));
            var env=context.getEnvironment();
            var unknown=org.springframework.boot.convert.DurationStyle.detectAndParse(env.getRequiredProperty("payment.recovery.discovery.unknown-timeout-age"));
            var processing=org.springframework.boot.convert.DurationStyle.detectAndParse(env.getRequiredProperty("payment.recovery.discovery.stale-processing-age"));
            var pending=org.springframework.boot.convert.DurationStyle.detectAndParse(env.getRequiredProperty("payment.recovery.discovery.stale-pending-age"));
            var candidates=repo.findApprovalCandidates(now.minus(unknown),now.minus(processing));
            require(candidates.size()==13 && candidates.stream().allMatch(c->c.targetAttemptSeq()==1 && expected.containsKey(c.targetTrxNo())) && candidates.stream().map(c->c.targetTrxNo()).distinct().count()==13,"exact candidate set mismatch");
            require(repo.findCancelCandidates(now.minus(unknown),now.minus(pending)).isEmpty() && repo.findReversalCandidates(now.minus(pending)).isEmpty(),"foreign candidates");
            require(unknown.equals(Duration.ofSeconds(30)) && processing.equals(Duration.ofMinutes(1)) && pending.equals(Duration.ofMinutes(1)) && env.getRequiredProperty("payment.recovery.worker.lease-duration").equals("30s"),"recovery policy override forbidden");
            write(out.resolve("candidates.json"),candidates);
            { // Exactly one explicit recoveryJob invocation; no automatic replay.
                int pass=1;
                topology(cfgPath);
                identity(spring,cfg.get("payment")); identity(van,cfg.get("van")); backend(van,cfg);
                var execution=context.getBean(JobLauncher.class).run(context.getBean("recoveryJob",Job.class),new JobParametersBuilder().addString("b13.invocation",UUID.randomUUID().toString()).toJobParameters());
                write(out.resolve("job-"+pass+".json"),Map.of("id",execution.getId(),"parameters",execution.getJobParameters().toString(),"status",execution.getStatus(),"steps",execution.getStepExecutions().stream().map(s->Map.of("name",s.getStepName(),"status",s.getStatus(),"read",s.getReadCount(),"write",s.getWriteCount(),"exit",s.getExitStatus().toString())).toList()));
                var after=attempts(payment);var ts=tasks(payment);var hs=history(payment);
                write(out.resolve("post-"+pass+".json"),Map.of("attempts",after,"van",vanRows(van),"vanLedger",vanLedger(van),"tasks",ts,"history",hs));
                require(execution.getStatus()==BatchStatus.COMPLETED && execution.getStepExecutions().size()==2,"Job incomplete");
                require(beforeLedger.equals(vanLedger(van)),"VAN changed"); require(ts.size()==13,"Task count mismatch");
                for(var e:expected.entrySet()) {var a=find(after,"pos_trx",e.getKey()); var t=find(ts,"target_trx_no",e.getKey());boolean approved=e.getValue().equals("APPROVED");
                    require(Objects.equals(a.get("final_status"),approved?"APPROVED":null) && t.get("recovery_status").equals(approved?"RESOLVED":"RETRY_WAIT"),"result mismatch");
                    if(approved) {var v=find(beforeVan,"pos_trx",e.getKey());require(a.get("approval_no").equals(v.get("approval_no")) && a.get("van_trx_id").equals(v.get("van_trx_id")),"finalized identity mismatch");}
                    else require(a.equals(find(before,"pos_trx",e.getKey())),"unresolved attempt changed");
                }
                // All unrelated attempts remain identical.
                for(var row:before) if(!expected.containsKey(row.get("pos_trx"))) require(after.contains(row),"unrelated attempt changed");
                if(pass==1) {require(inquiries.size()==13 && hs.size()==13,"first pass Inquiry/History count");
                    for(var t:ts) {
                        var h=hs.stream().filter(r->r.get("recovery_task_id").equals(t.get("id"))).toList();
                        require(h.size()==1 && Objects.equals(h.getFirst().get("result"),t.get("recovery_status")) && h.getFirst().get("finished_at")!=null,"History result mismatch");
                        require(((Number)t.get("retry_count")).intValue()==("RESOLVED".equals(t.get("recovery_status"))?0:1),"retry count mismatch");
                        if("RETRY_WAIT".equals(t.get("recovery_status"))) require(Duration.between(((Timestamp)h.getFirst().get("finished_at")).toLocalDateTime(),((Timestamp)t.get("next_retry_at")).toLocalDateTime()).equals(Duration.ofMinutes(1)),"first backoff changed");
                    }
                    write(out.resolve("inquiries-first.json"),inquiries); write(out.resolve("resolved-baseline.json"),Map.of("attempts",after,"tasks",ts,"history",hs));}
            }
            write(out.resolve("result.json"),Map.of("status","RECOVERY_ASSERTIONS_PASSED_EXTERNAL_EVIDENCE_REQUIRED","inquiries",inquiries.size()));
        } finally {watch.interrupt();}
    }
    static JsonNode normalized(Object value) throws Exception {return JSON.readTree(JSON.writeValueAsBytes(value));}
    static JsonNode findNode(JsonNode rows,String key,String value) {for(JsonNode row:rows) if(value.equals(row.path(key).asText())) return row;throw new IllegalStateException("missing baseline");}
    static boolean containsNode(JsonNode rows,JsonNode value) {for(JsonNode row:rows) if(row.equals(value)) return true;return false;}
}
