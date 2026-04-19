local prefix = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local ttl = tonumber(ARGV[3])

local time = redis.call('TIME')
local now = tonumber(time[1])
local current_bucket = math.floor(now / window)
local previous_bucket = current_bucket - 1
local elapsed = now % window
local weight = (window - elapsed) / window

local cur_key = prefix .. ':' .. current_bucket
local prev_key = prefix .. ':' .. previous_bucket

local cur_count = tonumber(redis.call('GET', cur_key) or '0')
local prev_count = tonumber(redis.call('GET', prev_key) or '0')

local estimated = prev_count * weight + cur_count

if estimated >= limit then
  local retry_after = window - elapsed
  return {0, 0, retry_after}
end

redis.call('INCR', cur_key)
redis.call('EXPIRE', cur_key, ttl)
local remainCount = math.floor(limit - estimated - 1)
return {1, remainCount, 0}
