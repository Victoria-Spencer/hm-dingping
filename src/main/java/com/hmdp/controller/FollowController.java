package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Autowired
    private IFollowService followService;
    @Autowired
    private IUserService userService;

    @PutMapping("/{id}/{isFollow}")
    public Result Follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {
        followService.follow(followUserId, isFollow);
        return Result.ok();
    }

    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
        Boolean isFollowed = followService.isFollow(followUserId);
        return Result.ok(isFollowed);
    }

    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long id) {
        List<UserDTO> userDTOS = userService.followCommons(id);
        return Result.ok(userDTOS);
    }
}
