#!/usr/bin/env bash
set -euo pipefail

# create-scaffold.sh
# Usage:
#   1) 프로젝트 루트에서 실행 (build.gradle 있는 위치)
#      chmod +x create-scaffold.sh
#      ./create-scaffold.sh
#
#   2) 강제 덮어쓰기
#      ./create-scaffold.sh --force
#
# Notes:
# - 기존 파일이 있으면 기본은 생성 스킵(보호 모드)
# - MyBatis XML은 보통 resources 아래에 두는게 자연스러워서 resources/mybatis/mapper 로 생성함

FORCE="false"
if [[ "${1:-}" == "--force" ]]; then
  FORCE="true"
fi

ROOT_DIR="$(pwd)"
JAVA_BASE_DIR="${ROOT_DIR}/src/main/java"
RES_BASE_DIR="${ROOT_DIR}/src/main/resources"

BASE_PKG="com.chaeyoungmin.paymentsim"
BASE_DIR="${JAVA_BASE_DIR}/com/chaeyoungmin/paymentsim"

mkdir -p "${JAVA_BASE_DIR}" "${RES_BASE_DIR}"

write_file() {
  local path="$1"
  local content="$2"

  mkdir -p "$(dirname "$path")"

  if [[ -f "$path" && "$FORCE" != "true" ]]; then
    echo "[SKIP] exists: $path"
    return
  fi

  printf "%s" "$content" > "$path"
  echo "[WRITE] $path"
}

pkg_decl() {
  local pkg="$1"
  echo "package ${pkg};"
}

# -----------------------------
# 1) Directories
# -----------------------------
mkdir -p \
  "${BASE_DIR}/common/api" \
  "${BASE_DIR}/common/exception" \
  "${BASE_DIR}/common/request" \
  "${BASE_DIR}/common/util" \
  "${BASE_DIR}/api/postrx/dto" \
  "${BASE_DIR}/api/postrx/service" \
  "${BASE_DIR}/api/payment/dto" \
  "${BASE_DIR}/api/payment/service/impl" \
  "${BASE_DIR}/api/payment/service/component" \
  "${BASE_DIR}/domain/model" \
  "${BASE_DIR}/domain/policy" \
  "${BASE_DIR}/infra/repository" \
  "${BASE_DIR}/infra/mybatis/config" \
  "${BASE_DIR}/van/gateway" \
  "${BASE_DIR}/van/dto" \
  "${RES_BASE_DIR}/db" \
  "${RES_BASE_DIR}/mybatis/mapper"

# -----------------------------
# 2) Root Application
# -----------------------------
write_file "${BASE_DIR}/PaymentSimApplication.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}")

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PaymentSimApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentSimApplication.class, args);
    }
}
EOF
)"

# -----------------------------
# 3) common/api
# -----------------------------
write_file "${BASE_DIR}/common/api/ResultCode.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.common.api")

public enum ResultCode {
    OK,
    DECLINED,
    UNKNOWN_TIMEOUT,
    RETRY_LATER,
    CONFLICT,
    INVALID,
    NOT_FOUND,
    ALREADY_CANCELLED,
    CANCEL_NOT_ALLOWED
}
EOF
)"

write_file "${BASE_DIR}/common/api/ApiResponse.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.common.api")

public class ApiResponse<T> {
    private String result_code;
    private String message;
    private T data;

    public ApiResponse() {}

    public ApiResponse(String result_code, String message, T data) {
        this.result_code = result_code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", null, data);
    }

    public static <T> ApiResponse<T> of(ResultCode code, String message, T data) {
        return new ApiResponse<>(code.name(), message, data);
    }

    public String getResult_code() { return result_code; }
    public void setResult_code(String result_code) { this.result_code = result_code; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
}
EOF
)"

write_file "${BASE_DIR}/common/api/ErrorResponse.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.common.api")

public class ErrorResponse {
    private String error;
    private String detail;

    public ErrorResponse() {}

    public ErrorResponse(String error, String detail) {
        this.error = error;
        this.detail = detail;
    }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
EOF
)"

# -----------------------------
# 4) common/exception
# -----------------------------
write_file "${BASE_DIR}/common/exception/BusinessException.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.common.exception")

import ${BASE_PKG}.common.api.ResultCode;

public class BusinessException extends RuntimeException {
    private final ResultCode resultCode;

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public ResultCode getResultCode() {
        return resultCode;
    }
}
EOF
)"

write_file "${BASE_DIR}/common/exception/ErrorCodeMapper.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.common.exception")

import ${BASE_PKG}.common.api.ResultCode;

public class ErrorCodeMapper {
    private ErrorCodeMapper() {}

    public static ResultCode map(Exception e) {
        // TODO: 예외 타입별 매핑 규칙 확정 후 구현
        return ResultCode.CONFLICT;
    }
}
EOF
)"

write_file "${BASE_DIR}/common/exception/GlobalExceptionHandler.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.common.exception")

import ${BASE_PKG}.common.api.ApiResponse;
import ${BASE_PKG}.common.api.ResultCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusiness(BusinessException e) {
        return ResponseEntity.ok(ApiResponse.of(e.getResultCode(), e.getMessage(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnknown(Exception e) {
        // TODO: 운영에서는 내부 에러 로깅 + 5xx 여부 정책 결정
        return ResponseEntity.ok(ApiResponse.of(ResultCode.CONFLICT, "Unhandled error", null));
    }
}
EOF
)"

# -----------------------------
# 5) common/request
# -----------------------------
write_file "${BASE_DIR}/common/request/RequestIdProvider.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.common.request")

public interface RequestIdProvider {
    String getOrCreate();
}
EOF
)"

write_file "${BASE_DIR}/common/request/RequestIdFilter.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.common.request")

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class RequestIdFilter extends OncePerRequestFilter {

    private final RequestIdProvider requestIdProvider;

    public RequestIdFilter(RequestIdProvider requestIdProvider) {
        this.requestIdProvider = requestIdProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // TODO: X-REQUEST-ID 수신/생성 + MDC 적용(필요시)
        requestIdProvider.getOrCreate();
        filterChain.doFilter(request, response);
    }
}
EOF
)"

# -----------------------------
# 6) common/util
# -----------------------------
write_file "${BASE_DIR}/common/util/ClockProvider.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.common.util")

import java.time.Instant;

public interface ClockProvider {
    Instant now();
}
EOF
)"

write_file "${BASE_DIR}/common/util/IdGenerator.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.common.util")

import java.util.UUID;

public class IdGenerator {
    private IdGenerator() {}

    public static String uuid() {
        return UUID.randomUUID().toString();
    }
}
EOF
)"

# -----------------------------
# 7) api/postrx
# -----------------------------
write_file "${BASE_DIR}/api/postrx/PosTrxController.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.api.postrx")

import ${BASE_PKG}.common.api.ApiResponse;
import ${BASE_PKG}.api.postrx.dto.PosTrxIssueRequest;
import ${BASE_PKG}.api.postrx.dto.PosTrxIssueResponse;
import ${BASE_PKG}.api.postrx.dto.PosTrxEotRequest;
import ${BASE_PKG}.api.postrx.dto.PosTrxEotResponse;
import ${BASE_PKG}.api.postrx.service.PosTrxService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/pos-trx")
public class PosTrxController {

    private final PosTrxService posTrxService;

    public PosTrxController(PosTrxService posTrxService) {
        this.posTrxService = posTrxService;
    }

    @PostMapping("/issue")
    public ApiResponse<PosTrxIssueResponse> issue(@RequestBody PosTrxIssueRequest request) {
        return ApiResponse.ok(posTrxService.issue(request));
    }

    @PostMapping("/eot")
    public ApiResponse<PosTrxEotResponse> eot(@RequestBody PosTrxEotRequest request) {
        return ApiResponse.ok(posTrxService.eot(request));
    }
}
EOF
)"

write_file "${BASE_DIR}/api/postrx/service/PosTrxService.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.api.postrx.service")

import ${BASE_PKG}.api.postrx.dto.PosTrxIssueRequest;
import ${BASE_PKG}.api.postrx.dto.PosTrxIssueResponse;
import ${BASE_PKG}.api.postrx.dto.PosTrxEotRequest;
import ${BASE_PKG}.api.postrx.dto.PosTrxEotResponse;

public interface PosTrxService {
    PosTrxIssueResponse issue(PosTrxIssueRequest request);
    PosTrxEotResponse eot(PosTrxEotRequest request);
}
EOF
)"

write_file "${BASE_DIR}/api/postrx/service/PosTrxServiceImpl.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.api.postrx.service")

import ${BASE_PKG}.api.postrx.dto.PosTrxIssueRequest;
import ${BASE_PKG}.api.postrx.dto.PosTrxIssueResponse;
import ${BASE_PKG}.api.postrx.dto.PosTrxEotRequest;
import ${BASE_PKG}.api.postrx.dto.PosTrxEotResponse;
import org.springframework.stereotype.Service;

@Service
public class PosTrxServiceImpl implements PosTrxService {

    @Override
    public PosTrxIssueResponse issue(PosTrxIssueRequest request) {
        // TODO: POS_TRX_SEQUENCE 사용해서 pos_trx 발급
        return new PosTrxIssueResponse(null);
    }

    @Override
    public PosTrxEotResponse eot(PosTrxEotRequest request) {
        // TODO: 다음 pos_trx 발급(EOT)
        return new PosTrxEotResponse(request.getStore_cd(), request.getBiz_date(), request.getPos_no(), null);
    }
}
EOF
)"

write_file "${BASE_DIR}/api/postrx/dto/PosTrxIssueRequest.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.api.postrx.dto")

public class PosTrxIssueRequest {
    private String store_cd;
    private String biz_date;
    private String pos_no;

    public String getStore_cd() { return store_cd; }
    public void setStore_cd(String store_cd) { this.store_cd = store_cd; }

    public String getBiz_date() { return biz_date; }
    public void setBiz_date(String biz_date) { this.biz_date = biz_date; }

    public String getPos_no() { return pos_no; }
    public void setPos_no(String pos_no) { this.pos_no = pos_no; }
}
EOF
)"

write_file "${BASE_DIR}/api/postrx/dto/PosTrxIssueResponse.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.api.postrx.dto")

public class PosTrxIssueResponse {
    private String pos_trx;

    public PosTrxIssueResponse() {}
    public PosTrxIssueResponse(String pos_trx) { this.pos_trx = pos_trx; }

    public String getPos_trx() { return pos_trx; }
    public void setPos_trx(String pos_trx) { this.pos_trx = pos_trx; }
}
EOF
)"

write_file "${BASE_DIR}/api/postrx/dto/PosTrxEotRequest.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.api.postrx.dto")

public class PosTrxEotRequest {
    private String pos_trx;
    private String store_cd;
    private String biz_date;
    private String pos_no;

    public String getPos_trx() { return pos_trx; }
    public void setPos_trx(String pos_trx) { this.pos_trx = pos_trx; }

    public String getStore_cd() { return store_cd; }
    public void setStore_cd(String store_cd) { this.store_cd = store_cd; }

    public String getBiz_date() { return biz_date; }
    public void setBiz_date(String biz_date) { this.biz_date = biz_date; }

    public String getPos_no() { return pos_no; }
    public void setPos_no(String pos_no) { this.pos_no = pos_no; }
}
EOF
)"

write_file "${BASE_DIR}/api/postrx/dto/PosTrxEotResponse.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.api.postrx.dto")

public class PosTrxEotResponse {
    private String store_cd;
    private String biz_date;
    private String pos_no;
    private String next_pos_trx;

    public PosTrxEotResponse() {}

    public PosTrxEotResponse(String store_cd, String biz_date, String pos_no, String next_pos_trx) {
        this.store_cd = store_cd;
        this.biz_date = biz_date;
        this.pos_no = pos_no;
        this.next_pos_trx = next_pos_trx;
    }

    public String getStore_cd() { return store_cd; }
    public void setStore_cd(String store_cd) { this.store_cd = store_cd; }

    public String getBiz_date() { return biz_date; }
    public void setBiz_date(String biz_date) { this.biz_date = biz_date; }

    public String getPos_no() { return pos_no; }
    public void setPos_no(String pos_no) { this.pos_no = pos_no; }

    public String getNext_pos_trx() { return next_pos_trx; }
    public void setNext_pos_trx(String next_pos_trx) { this.next_pos_trx = next_pos_trx; }
}
EOF
)"

# -----------------------------
# 8) api/payment
# -----------------------------
write_file "${BASE_DIR}/api/payment/PaymentController.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.api.payment")

import ${BASE_PKG}.common.api.ApiResponse;
import ${BASE_PKG}.api.payment.dto.*;
import ${BASE_PKG}.api.payment.service.PaymentApprovalService;
import ${BASE_PKG}.api.payment.service.PaymentInquiryService;
import ${BASE_PKG}.api.payment.service.PaymentCancelService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentApprovalService approvalService;
    private final PaymentInquiryService inquiryService;
    private final PaymentCancelService cancelService;

    public PaymentController(PaymentApprovalService approvalService,
                             PaymentInquiryService inquiryService,
                             PaymentCancelService cancelService) {
        this.approvalService = approvalService;
        this.inquiryService = inquiryService;
        this.cancelService = cancelService;
    }

    @PostMapping("/approve")
    public ApiResponse<ApproveResponse> approve(@RequestBody ApproveRequest request) {
        return approvalService.approve(request);
    }

    @PostMapping("/inquiry")
    public ApiResponse<InquiryResponse> inquiry(@RequestBody InquiryRequest request) {
        return inquiryService.inquiry(request);
    }

    @PostMapping("/cancel")
    public ApiResponse<CancelResponse> cancel(@RequestBody CancelRequest request) {
        return cancelService.cancel(request);
    }
}
EOF
)"

write_file "${BASE_DIR}/api/payment/service/PaymentApprovalService.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.api.payment.service")

import ${BASE_PKG}.common.api.ApiResponse;
import ${BASE_PKG}.api.payment.dto.ApproveRequest;
import ${BASE_PKG}.api.payment.dto.ApproveResponse;

public interface PaymentApprovalService {
    ApiResponse<ApproveResponse> approve(ApproveRequest request);
}
EOF
)"

write_file "${BASE_DIR}/api/payment/service/PaymentInquiryService.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.api.payment.service")

import ${BASE_PKG}.common.api.ApiResponse;
import ${BASE_PKG}.api.payment.dto.InquiryRequest;
import ${BASE_PKG}.api.payment.dto.InquiryResponse;

public interface PaymentInquiryService {
    ApiResponse<InquiryResponse> inquiry(InquiryRequest request);
}
EOF
)"

write_file "${BASE_DIR}/api/payment/service/PaymentCancelService.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.api.payment.service")

import ${BASE_PKG}.common.api.ApiResponse;
import ${BASE_PKG}.api.payment.dto.CancelRequest;
import ${BASE_PKG}.api.payment.dto.CancelResponse;

public interface PaymentCancelService {
    ApiResponse<CancelResponse> cancel(CancelRequest request);
}
EOF
)"

write_file "${BASE_DIR}/api/payment/service/impl/PaymentApprovalServiceImpl.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.api.payment.service.impl")

import ${BASE_PKG}.common.api.ApiResponse;
import ${BASE_PKG}.api.payment.dto.ApproveRequest;
import ${BASE_PKG}.api.payment.dto.ApproveResponse;
import ${BASE_PKG}.api.payment.service.PaymentApprovalService;
import org.springframework.stereotype.Service;

@Service
public class PaymentApprovalServiceImpl implements PaymentApprovalService {
    @Override
    public ApiResponse<ApproveResponse> approve(ApproveRequest request) {
        // TODO: A 프로세스 구현
        return ApiResponse.ok(new ApproveResponse());
    }
}
EOF
)"

write_file "${BASE_DIR}/api/payment/service/impl/PaymentInquiryServiceImpl.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.api.payment.service.impl")

import ${BASE_PKG}.common.api.ApiResponse;
import ${BASE_PKG}.api.payment.dto.InquiryRequest;
import ${BASE_PKG}.api.payment.dto.InquiryResponse;
import ${BASE_PKG}.api.payment.service.PaymentInquiryService;
import org.springframework.stereotype.Service;

@Service
public class PaymentInquiryServiceImpl implements PaymentInquiryService {
    @Override
    public ApiResponse<InquiryResponse> inquiry(InquiryRequest request) {
        // TODO: Q 프로세스 구현
        return ApiResponse.ok(new InquiryResponse());
    }
}
EOF
)"

write_file "${BASE_DIR}/api/payment/service/impl/PaymentCancelServiceImpl.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.api.payment.service.impl")

import ${BASE_PKG}.common.api.ApiResponse;
import ${BASE_PKG}.api.payment.dto.CancelRequest;
import ${BASE_PKG}.api.payment.dto.CancelResponse;
import ${BASE_PKG}.api.payment.service.PaymentCancelService;
import org.springframework.stereotype.Service;

@Service
public class PaymentCancelServiceImpl implements PaymentCancelService {
    @Override
    public ApiResponse<CancelResponse> cancel(CancelRequest request) {
        // TODO: C 프로세스 구현
        return ApiResponse.ok(new CancelResponse());
    }
}
EOF
)"

# payment DTOs (simple stubs)
write_file "${BASE_DIR}/api/payment/dto/ApproveRequest.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.api.payment.dto")

public class ApproveRequest {
    private String pos_trx;
    private int amount;
    private Card card;

    public String getPos_trx() { return pos_trx; }
    public void setPos_trx(String pos_trx) { this.pos_trx = pos_trx; }

    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }

    public Card getCard() { return card; }
    public void setCard(Card card) { this.card = card; }

    public static class Card {
        private String pan;
        private String expiry_yy_mm;

        public String getPan() { return pan; }
        public void setPan(String pan) { this.pan = pan; }

        public String getExpiry_yy_mm() { return expiry_yy_mm; }
        public void setExpiry_yy_mm(String expiry_yy_mm) { this.expiry_yy_mm = expiry_yy_mm; }
    }
}
EOF
)"

write_file "${BASE_DIR}/api/payment/dto/ApproveResponse.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.api.payment.dto")

public class ApproveResponse {
    // TODO: pos_trx, attempt_seq, approval_no, card_summary, decline_code 등
}
EOF
)"

write_file "${BASE_DIR}/api/payment/dto/InquiryRequest.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.api.payment.dto")

public class InquiryRequest {
    private String pos_trx;
    private int attempt_seq;

    public String getPos_trx() { return pos_trx; }
    public void setPos_trx(String pos_trx) { this.pos_trx = pos_trx; }

    public int getAttempt_seq() { return attempt_seq; }
    public void setAttempt_seq(int attempt_seq) { this.attempt_seq = attempt_seq; }
}
EOF
)"

write_file "${BASE_DIR}/api/payment/dto/InquiryResponse.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.api.payment.dto")

public class InquiryResponse {
    // TODO: pos_trx, attempt_seq, approval_no / decline_code 등
}
EOF
)"

write_file "${BASE_DIR}/api/payment/dto/CancelRequest.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.api.payment.dto")

public class CancelRequest {
    private String current_trx_no;
    private String original_trx_no;
    private int original_attempt_seq;

    public String getCurrent_trx_no() { return current_trx_no; }
    public void setCurrent_trx_no(String current_trx_no) { this.current_trx_no = current_trx_no; }

    public String getOriginal_trx_no() { return original_trx_no; }
    public void setOriginal_trx_no(String original_trx_no) { this.original_trx_no = original_trx_no; }

    public int getOriginal_attempt_seq() { return original_attempt_seq; }
    public void setOriginal_attempt_seq(int original_attempt_seq) { this.original_attempt_seq = original_attempt_seq; }
}
EOF
)"

write_file "${BASE_DIR}/api/payment/dto/CancelResponse.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.api.payment.dto")

public class CancelResponse {
    // TODO: current_trx_no, original_trx_no, original_attempt_seq, cancel_approval_no 등
}
EOF
)"

# -----------------------------
# 9) domain/model (POJO skeletons)
# -----------------------------
for m in PaymentAttempt PaymentCancel BinCatalog PosTrxSequence PaymentEventLog; do
  write_file "${BASE_DIR}/domain/model/${m}.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.domain.model")

public class ${m} {
    // TODO: 테이블 정의서 기준 필드로 채우기
}
EOF
)"
done

# -----------------------------
# 10) domain/policy (enum skeletons)
# -----------------------------
write_file "${BASE_DIR}/domain/policy/AttemptStatus.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.domain.policy")

public enum AttemptStatus {
    // NOTE: FINAL_STATUS는 NULL=처리중, 나머지는 문자열로 저장(문서 기준)
    APPROVED,
    DECLINED,
    UNKNOWN_TIMEOUT
}
EOF
)"

write_file "${BASE_DIR}/domain/policy/CancelStatus.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.domain.policy")

public enum CancelStatus {
    PENDING,
    CANCELLED,
    CANCEL_DECLINED
}
EOF
)"

write_file "${BASE_DIR}/domain/policy/DeclineCode.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.domain.policy")

public enum DeclineCode {
    D001,
    D101,
    D102,
    D201,
    D202,
    D901
}
EOF
)"

# -----------------------------
# 11) infra/repository (interfaces)
# -----------------------------
for r in PaymentAttemptRepository PaymentCancelRepository BinCatalogRepository PosTrxSequenceRepository PaymentEventLogRepository; do
  write_file "${BASE_DIR}/infra/repository/${r}.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.infra.repository")

public interface ${r} {
    // TODO: MyBatis 매퍼/쿼리 확정 후 메서드 정의
}
EOF
)"
done

# -----------------------------
# 12) infra/mybatis/config
# -----------------------------
write_file "${BASE_DIR}/infra/mybatis/config/MyBatisConfig.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.infra.mybatis.config")

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan(basePackages = "${BASE_PKG}.infra")
public class MyBatisConfig {
    // TODO: 필요 시 SqlSessionFactory/TypeHandler/MapperLocations 설정
}
EOF
)"

# -----------------------------
# 13) van/gateway + van/dto
# -----------------------------
write_file "${BASE_DIR}/van/gateway/VanGateway.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.van.gateway")

import ${BASE_PKG}.van.dto.*;

public interface VanGateway {
    VanApproveResponse approve(VanApproveRequest request);
    VanInquiryResponse inquiry(VanInquiryRequest request);
    VanCancelResponse cancel(VanCancelRequest request);
}
EOF
)"

write_file "${BASE_DIR}/van/gateway/SimulatedVanGateway.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.van.gateway")

import ${BASE_PKG}.van.dto.*;
import org.springframework.stereotype.Component;

@Component
public class SimulatedVanGateway implements VanGateway {

    @Override
    public VanApproveResponse approve(VanApproveRequest request) {
        // TODO: 시뮬레이터 규칙 구현
        return new VanApproveResponse();
    }

    @Override
    public VanInquiryResponse inquiry(VanInquiryRequest request) {
        // TODO: 시뮬레이터 규칙 구현
        return new VanInquiryResponse();
    }

    @Override
    public VanCancelResponse cancel(VanCancelRequest request) {
        // TODO: 시뮬레이터 규칙 구현
        return new VanCancelResponse();
    }
}
EOF
)"

for d in VanApproveRequest VanApproveResponse VanInquiryRequest VanInquiryResponse VanCancelRequest VanCancelResponse; do
  write_file "${BASE_DIR}/van/dto/${d}.java" "$(cat <<EOF
$(pkg_decl "${BASE_PKG}.van.dto")

public class ${d} {
    // TODO: VAN 호출 DTO (입력값 그대로 전달 원칙 기반)
}
EOF
)"
done

# -----------------------------
# 14) resources: schema.sql + mybatis mappers placeholders
# -----------------------------
write_file "${RES_BASE_DIR}/db/schema.sql" "$(cat <<EOF
-- schema.sql (SQLite)
-- TODO: 테이블 정의서 기준으로 DDL 작성
EOF
)"

for x in PaymentAttemptMapper PaymentCancelMapper BinCatalogMapper PosTrxSequenceMapper PaymentEventLogMapper; do
  write_file "${RES_BASE_DIR}/mybatis/mapper/${x}.xml" "$(cat <<EOF
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper
  PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
  "http://mybatis.org/dtd/mybatis-3-mapper.dtd">

<mapper namespace="${BASE_PKG}.infra.mybatis.mapper.${x}">
  <!-- TODO: SQL 매핑 작성 -->
</mapper>
EOF
)"
done

# -----------------------------
# 15) resources: application.yml (optional)
# -----------------------------
write_file "${RES_BASE_DIR}/application.yml" "$(cat <<EOF
server:
  port: 8080

spring:
  application:
    name: payment-sim

# TODO: SQLite DataSource + MyBatis 설정 추가
EOF
)"

echo ""
echo "Done. Scaffold generated under:"
echo "  - ${BASE_DIR}"
echo "  - ${RES_BASE_DIR}"
echo ""
echo "Tip: --force 옵션을 주면 기존 파일도 덮어씁니다."
