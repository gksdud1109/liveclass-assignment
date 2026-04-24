package com.liveclass.backend.global.error.handler;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.liveclass.backend.global.error.code.CommonErrorCode;
import com.liveclass.backend.global.error.code.ErrorCode;
import com.liveclass.backend.global.error.exception.BusinessException;
import com.liveclass.backend.global.error.exception.ExternalApiException;
import com.liveclass.backend.global.response.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
		ErrorCode code = ex.getErrorCode();
		String codeName = (code instanceof Enum<?> e) ? e.name() : code.getClass().getSimpleName();

		if (code.getHttpStatus().is5xxServerError()) {
			log.error("BusinessException: {} - {}", codeName, ex.getMessage());
		} else {
			log.warn("BusinessException: {} - {}", codeName, ex.getMessage());
		}

		return ResponseEntity
			.status(code.getHttpStatus())
			.body(ErrorResponse.from(ex));
	}

	// @Valid 유효성 검사 실패: 조합한 필드 메시지를 응답에 포함
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
		String message = ex.getBindingResult().getFieldErrors().stream()
			.map(e -> e.getField() + ": " + e.getDefaultMessage())
			.reduce((a, b) -> a + ", " + b)
			.orElse(CommonErrorCode.INVALID_INPUT_VALUE.getMessage());

		log.warn("Validation failed: {}", message);

		return ResponseEntity
			.status(CommonErrorCode.INVALID_INPUT_VALUE.getHttpStatus())
			.contentType(MediaType.APPLICATION_JSON)
			.body(new ErrorResponse(
				CommonErrorCode.INVALID_INPUT_VALUE.getHttpStatus().value(),
				CommonErrorCode.INVALID_INPUT_VALUE.name(),
				message));
	}

	// 타입 불일치, JSON 파싱 실패
	@ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
	public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
		log.warn("Bad request: {}", ex.getMessage());

		return ResponseEntity
			.status(CommonErrorCode.INVALID_INPUT_VALUE.getHttpStatus())
			.contentType(MediaType.APPLICATION_JSON)
			.body(ErrorResponse.from(CommonErrorCode.INVALID_INPUT_VALUE));
	}

	// 외부 API(PG) 호출 실패: timeout, 4xx/5xx 응답
	@ExceptionHandler(ExternalApiException.class)
	public ResponseEntity<ErrorResponse> handleExternalApi(ExternalApiException ex) {
		log.error("ExternalApiException: {} - {}", ex.getCode(), ex.getMessage());

		return ResponseEntity
			.status(ex.getHttpStatus())
			.contentType(MediaType.APPLICATION_JSON)
			.body(new ErrorResponse(ex.getHttpStatus().value(), ex.getCode(), ex.getMessage()));
	}

	// 백스톱: 서비스 레이어에서 잡지 못한 DataIntegrityViolationException
	// 서비스에서 잡힌 경우는 멱등 처리 후 정상 응답으로 반환됨
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ErrorResponse> handleDuplicateKey(DataIntegrityViolationException ex) {
		log.warn("Data integrity violation: {}", ex.getMessage());

		return ResponseEntity
			.status(CommonErrorCode.DUPLICATE_KEY.getHttpStatus())
			.contentType(MediaType.APPLICATION_JSON)
			.body(ErrorResponse.from(CommonErrorCode.DUPLICATE_KEY));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnknown(Exception ex){
		log.error("Unexpected error: {}", ex.getMessage(), ex);

		return ResponseEntity
			.status(CommonErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus())
			.contentType(MediaType.APPLICATION_JSON)
			.body(ErrorResponse.from(CommonErrorCode.INTERNAL_SERVER_ERROR));
	}
}
