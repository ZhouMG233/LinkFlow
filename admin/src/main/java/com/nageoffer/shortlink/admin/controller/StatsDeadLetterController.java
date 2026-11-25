/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.shortlink.admin.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.common.convention.result.Results;
import com.nageoffer.shortlink.admin.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 统计死信查询与重放
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/short-link/admin/v1/stats/dead-letters")
public class StatsDeadLetterController {

    // 与 project 的统计消费者共用 Redis 和消息格式。
    private static final String DEAD_LETTER_STREAM = "short-link:stats-stream:dead-letter";
    private static final String STATS_STREAM = "short-link:stats-stream";
    private static final int PAGE_SIZE = 20;
    private static final Pattern STREAM_ID = Pattern.compile("(0|[1-9][0-9]{0,19})-(0|[1-9][0-9]{0,19})");
    private static final RedisScript<String> REPLAY = RedisScript.of("""
            if #redis.call('XRANGE', KEYS[1], ARGV[1], ARGV[1]) == 0 then
                return nil
            end
            local id = redis.call('XADD', KEYS[2], '*', 'statsRecord', ARGV[2], 'retryCount', '0')
            redis.call('XDEL', KEYS[1], ARGV[1])
            return id
            """, String.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final UserService userService;

    @Value("${LINKFLOW_STATS_DEAD_LETTER_OPERATORS:}")
    private String operators;

    @GetMapping
    public ResponseEntity<Result<DeadLetterPage>> list(HttpServletRequest request,
                                                      @RequestParam(value = "before", required = false) String before) {
        requireOperator(request);
        Range<String> range = Range.unbounded();
        if (before != null) {
            validateId(before);
            range = Range.leftUnbounded(Range.Bound.inclusive(before));
        }
        // 使用包含端点的范围兼容 Redis 5，再排除上一页的末条记录。
        List<DeadLetter> rows = stringRedisTemplate.<String, String>opsForStream()
                .reverseRange(DEAD_LETTER_STREAM, range, Limit.limit().count(PAGE_SIZE + 2))
                .stream()
                .filter(record -> !record.getId().getValue().equals(before))
                .limit(PAGE_SIZE + 1L)
                .map(record -> new DeadLetter(
                        record.getId().getValue(),
                        Integer.parseInt(record.getValue().get("retryCount")),
                        record.getValue().get("error"),
                        Long.parseLong(record.getValue().get("failedAt"))))
                .toList();
        boolean hasMore = rows.size() > PAGE_SIZE;
        DeadLetterPage page = hasMore
                ? new DeadLetterPage(rows.subList(0, PAGE_SIZE), rows.get(PAGE_SIZE - 1).id())
                : new DeadLetterPage(rows, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Results.success(page));
    }

    @PostMapping("/replay")
    public Result<String> replay(HttpServletRequest request, @RequestParam(value = "id", required = false) String id) {
        String username = requireOperator(request);
        validateId(id);
        List<MapRecord<String, String, String>> records = stringRedisTemplate.<String, String>opsForStream()
                .range(DEAD_LETTER_STREAM, Range.closed(id, id), Limit.limit().count(1));
        if (records.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "死信已重放或不存在");
        }
        String payload = records.get(0).getValue().get("statsRecord");
        validatePayload(payload);
        String newId = stringRedisTemplate.execute(REPLAY, List.of(DEAD_LETTER_STREAM, STATS_STREAM), id, payload);
        if (newId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "死信已重放或不存在");
        }
        log.info("统计死信已入队，操作人={}，死信ID={}，新消息ID={}", username, id, newId);
        return Results.success(newId);
    }

    private String requireOperator(HttpServletRequest request) {
        String username = request.getHeader("username");
        String token = request.getHeader("token");
        if (!StringUtils.hasText(username) || !StringUtils.hasText(token)
                || !Boolean.TRUE.equals(userService.checkLogin(username, token))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        boolean allowed = Arrays.stream(operators.split(",")).map(String::trim).anyMatch(username::equals);
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号没有统计死信管理权限");
        }
        return username;
    }

    private void validateId(String id) {
        if (id == null || !STREAM_ID.matcher(id).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "死信 ID 格式错误");
        }
        int separator = id.indexOf('-');
        try {
            Long.parseUnsignedLong(id.substring(0, separator));
            Long.parseUnsignedLong(id.substring(separator + 1));
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "死信 ID 格式错误");
        }
    }

    private void validatePayload(String payload) {
        JSONObject statsRecord;
        try {
            statsRecord = JSON.parseObject(payload);
        } catch (JSONException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "统计消息格式错误，无法重放");
        }
        if (statsRecord == null || !(statsRecord.get("keys") instanceof String keys) || !StringUtils.hasText(keys)
                || !(statsRecord.get("fullShortUrl") instanceof String shortUrl) || !StringUtils.hasText(shortUrl)
                || statsRecord.get("currentDate") == null || !(statsRecord.get("uvFirstFlag") instanceof Boolean)
                || !(statsRecord.get("uipFirstFlag") instanceof Boolean)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "统计消息缺少有效业务键或必要访问数据，无法重放");
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> handleStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .cacheControl(CacheControl.noStore())
                .body(Results.failure(String.valueOf(ex.getStatusCode().value()), ex.getReason()));
    }

    public record DeadLetter(String id, int retryCount, String error, long failedAt) {
    }

    public record DeadLetterPage(List<DeadLetter> items, String nextBefore) {
    }
}
