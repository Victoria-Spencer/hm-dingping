package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
