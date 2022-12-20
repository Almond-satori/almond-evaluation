package com.almond.controller;


import com.almond.dto.Result;
import com.almond.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService iFollowService;

    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId ,@PathVariable("isFollow") Boolean isFollow){
        return iFollowService.follow(followUserId,isFollow);
    }

    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId){
        return iFollowService.isFollow(followUserId);
    }

    @GetMapping("/common/{id}")
    public Result common(@PathVariable("id") Long followUserId){
        return iFollowService.common(followUserId);
    }

}
