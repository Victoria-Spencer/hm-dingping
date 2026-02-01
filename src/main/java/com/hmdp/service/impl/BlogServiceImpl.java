package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.apache.tomcat.util.buf.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Autowired
    private IBlogService blogService;
    @Autowired
    private IFollowService followService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<Blog> queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            // 查询blog是否被当前用户点赞
            this.isBlogLiked(blog);
        });
        return records;
    }

    @Override
    public Blog queryBlog(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            throw new RuntimeException("博客不存在");
        }
        // 2.查询blog有关的用户
        queryBlogUser(blog);
        // 3.查询blog是否被当前用户点赞
        isBlogLiked(blog);
        return blog;
    }

    private void isBlogLiked(Blog blog) {
        // 1.获取登录用户
        UserDTO userDTO = UserHolder.getUser();
        if(userDTO == null){
            return;
        }
        Long userId = userDTO.getId();
        // 2.判断当前登录用户是否点赞过
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + blog.getId(), userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public void likeBlog(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;
        // 2.判断当前登录用户是否点赞过
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 3.如果未点赞，可以点赞
            // 3.1.数据库点赞次数 + 1
            boolean isSuccess = update().setSql("liked = liked  + 1")
                    .eq("id", id).update();
            // 3.2.保存用户到redis的set集合中
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4.已点赞，取消点赞
            // 4.1.数据库点赞次数 - 1
            boolean isSuccess = update().setSql("liked = liked  - 1")
                    .eq("id", id).update();
            // 4.2.移除redis中set集合里的用户信息
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
    }

    @Override
    public List<UserDTO> queryBlogLikes(Long id) {
        // 1.查询top5的点赞用户 zrange key 0 4
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        // 空值处理：如果没有点赞用户，直接返回空列表
        if (top5 == null || top5.isEmpty()) {
            return Collections.emptyList();
        }

        // 2.解析出其中的用户id
        List<Long> ids = top5.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());

        // 3.根据用户id批量查询用户信息
        String join = StrUtil.join(",", ids);
        return userService
                .list(new QueryWrapper<User>()
                        .in("id", ids)
                        .last("ORDER BY FIELD(id, " + join + ")") // 强制按ids顺序排序
                )
                .stream()
                .map(user-> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
    }

    @Override
    public Long saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店博文
        boolean isSuccess = blogService.save(blog);
        if (!isSuccess) {
            throw new RuntimeException("新增笔记失败!");
        }
        // 3.获取笔记作者的所有粉丝，select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 4.推送笔记id给所有粉丝
        for (Follow followUserId : follows) {
            String key = FEED_KEY + followUserId.getUserId().toString();
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 4.返回id
        return blog.getId();
    }

    @Override
    public ScrollResult queryBlogOfFollow(Long lastId, Integer offset) {
        // 1.获取当前用户
        Long id = UserHolder.getUser().getId();
        String key = FEED_KEY + id;

        // 2.在redis中，查询blogId
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, lastId, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return null;
        }

        // 3.解析typedTuples, 提取blogId集合，minTime, newOffset
        List<String> blogIds = new ArrayList<>(); // 存储提取的blogId集合
        Long minTime = 0L; // 本次结果中最小的score（下一页的lastId）
        int sameScoreCount = 0; // 当前页中与minTime相 同的元素个数

        // 3.1.将Set转成List，方便获取最后一个元素（求minTime）和判断结果数量（求newOffset）
        List<ZSetOperations.TypedTuple<String>> tupleList = new ArrayList<>(typedTuples);
        // 3.2.流式提取blogId
        blogIds = tupleList.stream()
                .map(ZSetOperations.TypedTuple::getValue) // 从Tuple中提取blogId
                .filter(Objects::nonNull) // 防止null值
                .collect(Collectors.toList()); // 收集成blogId列表

        // 3.3.获取minTime（最后一个元素的score，转Long，与lastId类型一致）
        ZSetOperations.TypedTuple<String> lastTuple = tupleList.get(tupleList.size() - 1);
        if (lastTuple.getScore() != null) {
            minTime = lastTuple.getScore().longValue(); // Double→Long，适配lastId类型
            // 从尾部遍历，统计sameScoreCount
            // --------------------------
            double targetScore = lastTuple.getScore(); // 目标score（minTime）
            // 从列表尾部向前遍历，统计连续等于targetScore的元素个数
            for (int i = tupleList.size() - 1; i >= 0; i--) {
                ZSetOperations.TypedTuple<String> tuple = tupleList.get(i);
                if (tuple.getScore() != null && Double.compare(tuple.getScore(), targetScore) == 0) {
                    sameScoreCount++; // 相同则计数+1
                } else {
                    break; // 遇到不同score，直接终止遍历（仅统计连续的）
                }
            }
        }

        int newOffset = 0; // 下一页的偏移量
        if (minTime.equals(lastId)) {
            // 相同：同一score未取完，offset=上一页offset + 本次同score个数
            newOffset = offset.intValue() + sameScoreCount;
        } else {
            // 不同：score已切换，newOffset=本次同score个数
            newOffset = sameScoreCount;
        }

        // 4.根据blogIds查询博客 - 保持Redis的顺序（避免数据库乱序）
        String blogIdStr = StrUtil.join(",", blogIds);
        List<Blog> blogs = blogService.list(
                new QueryWrapper<Blog>()
                        .in("id", blogIds)
                        .last("ORDER BY FIELD(id, " + blogIdStr + ")")
        );

        for (Blog blog : blogs) {
            // 4.1.查询blog有关的用户
            queryBlogUser(blog);
            // 4.2.查询blog是否被当前用户点赞
            isBlogLiked(blog);
        }

        // 5.封装返回
        ScrollResult sr = new ScrollResult();
        sr.setList(blogs);
        sr.setMinTime(minTime);
        sr.setOffset(newOffset);
        return sr;
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
