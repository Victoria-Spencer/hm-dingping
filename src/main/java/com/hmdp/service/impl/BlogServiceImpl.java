package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

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
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前登录用户是否点赞过
        Boolean isLiked = stringRedisTemplate.opsForSet().isMember(BLOG_LIKED_KEY + blog.getId(), userId.toString());
        blog.setIsLike(BooleanUtil.isTrue(isLiked));
    }

    @Override
    public void likeBlog(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;
        // 2.判断当前登录用户是否点赞过
        Boolean isLiked = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if (BooleanUtil.isFalse(isLiked)) {
            // 3.如果未点赞，可以点赞
            // 3.1.数据库点赞次数 + 1
            boolean isSuccess = update().setSql("liked = liked  + 1")
                    .eq("id", id).update();
            // 3.2.保存用户到redis的set集合中

            if (isSuccess) {
                stringRedisTemplate.opsForSet().add(key, userId.toString());
            }
        } else {
            // 4.已点赞，取消点赞
            // 4.1.数据库点赞次数 - 1
            boolean isSuccess = update().setSql("liked = liked  - 1")
                    .eq("id", id).update();
            // 4.2.移除redis中set集合里的用户信息
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
            }
        }
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
