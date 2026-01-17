---
--- Created by 31031.
--- DateTime: 2025/12/7 15:51
---

-- 判断当前线程的标识与锁的标识是否一致
if redis.call("GET", KEYS[1]) == ARGV[1] then
    -- 一致则删除锁，释放锁
    return redis.call("DEL", KEYS[1])
end
return 0