package com.example.run_or_walk

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Categorized metadata only; raw gateway text, request URLs and credentials are discarded. */
internal class GyeonggiApiException(
    message: String, val httpStatus: Int, val serverCode: String?, val retryAfterEpochMillis: Long?
) : IOException(message)

internal fun parseGyeonggiHttpFailure(status: Int, body: String?, retryAfter: String?, now: Long): GyeonggiApiException {
    val error = body?.takeIf { it.length <= 8_192 }?.let { runCatching { JSONObject(it).optJSONObject("error") }.getOrNull() }
    val code = (error?.opt("code") as? String)?.takeIf { it in gyeonggiErrorCodes }
    fun bounded(value: Long?) = value?.takeIf { it > now && it - now <= 172_800_000L }
    val headerAt = retryAfter?.trim()?.let { text ->
        text.toLongOrNull()?.takeIf { it in 1..172_800L }?.let { now + it * 1_000L }
            ?: runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    }?.let(::bounded)
    val seconds = (error?.opt("retryAfterSeconds") as? Number)?.toDouble()?.takeIf {
        it.isFinite() && it in 1.0..172_800.0 && it % 1.0 == 0.0
    }
    val retryAt = listOfNotNull(headerAt, seconds?.let { now + it.toLong() * 1_000L }).maxOrNull()
        ?: if (status == 429) now + 30_000L else null
    val message = when {
        code == "GYEONGGI_NOT_CONFIGURED" -> "경기버스 중계 서버에 해당 서비스의 연결 설정이 없어요. 서버 설정 확인이 필요해요."
        code == "GYEONGGI_KEY_REJECTED" -> "경기버스 원본 서버가 인증 요청을 거절했어요(401). 등록된 키와 요청 형식을 확인해 주세요."
        code == "GYEONGGI_ACCESS_FORBIDDEN" -> "경기버스 원본 서버가 접근을 거절했어요(403). 승인 서비스와 접속 조건을 확인해 주세요."
        code == "GYEONGGI_GATEWAY_KEY_REJECTED" -> "공공데이터 중계에서 경기버스 인증키를 인식하지 못했어요. 서버에 등록된 키를 확인해 주세요."
        code == "GYEONGGI_GATEWAY_KEY_EXPIRED" -> "공공데이터 중계에서 경기버스 키의 이용기간 만료를 알렸어요."
        code == "GYEONGGI_GATEWAY_ACCESS_FORBIDDEN" -> "공공데이터 중계에서 경기버스 서비스 접근을 거절했어요. 승인 범위와 접속 조건 확인이 필요해요."
        status == 429 -> "경기버스 조회가 잠시 제한됐어요. 서버가 안내한 재조회 시각 이후 다시 확인해요."
        status in setOf(401, 403) -> "경기버스 중계 서버가 앱 요청을 거절했어요. 서버 접근 설정을 확인해 주세요."
        status == 404 || (status == 400 && code == "GYEONGGI_INVALID_QUERY") -> "경기버스 중계 서버의 요청 경로를 확인해 주세요. 최신 Worker 배포가 필요할 수 있어요."
        code == "GYEONGGI_KEY_INVALID" -> "경기버스 서버에 등록된 인증키의 형식을 확인해 주세요."
        else -> "경기버스 원본·중계 서버에서 응답을 확인하지 못했어요. 잠시 뒤 다시 조회해요."
    }
    return GyeonggiApiException(message, status, code, retryAt)
}

internal fun readGyeonggiHttpFailure(connection: HttpURLConnection, status: Int): GyeonggiApiException {
    val body = runCatching { connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
        val value = StringBuilder()
        val buffer = CharArray(1_024)
        while (value.length <= 8_192) {
            val count = reader.read(buffer)
            if (count < 0) return@use value.toString()
            value.append(buffer, 0, count)
        }
        null
    } }.getOrNull()
    return parseGyeonggiHttpFailure(status, body, connection.getHeaderField("Retry-After"), System.currentTimeMillis())
}

internal class GyeonggiRequestBackoff {
    private var blocked: GyeonggiApiException? = null
    @Synchronized fun record(failure: GyeonggiApiException) {
        if ((failure.retryAfterEpochMillis ?: Long.MIN_VALUE) > (blocked?.retryAfterEpochMillis ?: Long.MIN_VALUE)) blocked = failure
    }
    @Synchronized fun check(now: Long) {
        blocked?.let { if ((it.retryAfterEpochMillis ?: 0L) > now) throw it }
        blocked = null
    }
}

private val gyeonggiErrorCodes = setOf("GYEONGGI_NOT_CONFIGURED", "GYEONGGI_INVALID_QUERY", "GYEONGGI_KEY_INVALID",
    "GYEONGGI_KEY_REJECTED", "GYEONGGI_ACCESS_FORBIDDEN", "GYEONGGI_AUTHENTICATION", "GYEONGGI_UPSTREAM_REJECTED",
    "GYEONGGI_GATEWAY_KEY_REJECTED", "GYEONGGI_GATEWAY_KEY_EXPIRED", "GYEONGGI_GATEWAY_ACCESS_FORBIDDEN",
    "GYEONGGI_GATEWAY_RATE_LIMITED", "GYEONGGI_GATEWAY_REJECTED", "GYEONGGI_DAILY_BUDGET", "GYEONGGI_UPSTREAM_RATE_LIMIT",
    "GYEONGGI_TIMEOUT", "GYEONGGI_SERVICE_UNAVAILABLE", "GYEONGGI_UPSTREAM_UNAVAILABLE", "IP_RATE_LIMITED",
    "GYEONGGI_INVALID_RESPONSE", "CLIENT_IP_UNAVAILABLE", "RATE_LIMITER_UNAVAILABLE")
