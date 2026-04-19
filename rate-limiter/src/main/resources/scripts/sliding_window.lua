-- KEYS[1]: Rate limit key prefix (e.g., "rl:/api/foo")
-- ARGV[1]: Max limit (e.g., 100)
-- ARGV[2]: Window size in seconds (e.g., 60)
-- ARGV[3]: Redis key TTL in seconds (e.g., 120)

local prefix = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local ttl = tonumber(ARGV[3])

-- 1. 현재 시간 및 버킷 정보 계산
local time = redis.call('TIME')
local now = tonumber(time[1])
local current_bucket = math.floor(now / window)
local previous_bucket = current_bucket - 1

-- 2. 현재 버킷 내 경과 시간 및 가중치(weight) 계산
-- weight는 이전 버킷의 카운트가 현재 슬라이딩 윈도우에 얼마나 포함되는지를 결정함
local elapsed = now % window
local weight = (window - elapsed) / window

-- 3. 현재 및 이전 버킷의 키 생성 및 카운트 조회
local cur_key = prefix .. ':' .. current_bucket
local prev_key = prefix .. ':' .. previous_bucket

local cur_count = tonumber(redis.call('GET', cur_key) or '0')
local prev_count = tonumber(redis.call('GET', prev_key) or '0')

-- 4. 가중 평균을 이용한 현재 요청 수 예측 (Estimation)
local estimated = prev_count * weight + cur_count

-- 5. 한도 초과 여부 확인
if estimated >= limit then
  -- 차단 시: 다음 버킷이 시작될 때까지 남은 시간을 반환
  -- 이는 클라이언트의 빈번한 재시도를 억제하여 시스템 부하를 최소화하기 위한 보수적인 전략임
  local retry_after = window - elapsed
  return {0, 0, retry_after}
end

-- 6. 요청 허용: 현재 버킷 카운트 증가 및 TTL 설정
redis.call('INCR', cur_key)
redis.call('EXPIRE', cur_key, ttl)

-- 7. 남은 횟수 계산 및 결과 반환 (allowed=1, remainCount, retryAfter=0)
local remainCount = math.floor(limit - estimated - 1)
return {1, remainCount, 0}
