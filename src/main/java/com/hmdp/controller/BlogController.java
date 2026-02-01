package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return Result.ok(blogService.saveBlog(blog));
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        blogService.likeBlog(id);
        return Result.ok();
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        List<Blog> records = blogService.queryHotBlog(current);
        return Result.ok(records);
    }

    @GetMapping("/{id}")
    public Result queryBlog(@PathVariable("id") Long id) {
        Blog blog = blogService.queryBlog(id);
        return Result.ok(blog);
    }

    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        List<UserDTO> userDTOList = blogService.queryBlogLikes(id);
        return Result.ok(userDTOList);
    }

    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        // 根据用户分页查询
        Page<Blog> blogPage = blogService.query()
                .eq("user_id", id)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回当前页数据
        List<Blog> records = blogPage.getRecords();
        return Result.ok(records);
    }

    // http://localhost:8080/api/blog/of/follow?&lastId=1769862179757
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam(value = "lastId") Long lastId,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        return Result.ok(blogService.queryBlogOfFollow(lastId, offset));
    }
}
