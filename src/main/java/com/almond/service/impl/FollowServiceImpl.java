package com.almond.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.almond.dto.Result;
import com.almond.dto.UserDTO;
import com.almond.entity.Follow;
import com.almond.entity.User;
import com.almond.mapper.FollowMapper;
import com.almond.service.IFollowService;
import com.almond.service.IUserService;
import com.almond.utils.UserHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.almond.utils.RedisConstants.USER_FOLLOW_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService iUserService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //根据用户关注/取关 在数据库中 增加/删除 关系表元素
        Long userId = UserHolder.getUser().getId();
        if(isFollow){
            //关注
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean saved = save(follow);
            if(saved){
                stringRedisTemplate.opsForSet().add(USER_FOLLOW_KEY + userId, followUserId.toString());
            }
        }else{
            //取关
            LambdaQueryWrapper<Follow> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Follow::getUserId, userId).eq(Follow::getFollowUserId,followUserId);
            boolean removed = remove(wrapper);
            if(removed){
                stringRedisTemplate.opsForSet().remove(USER_FOLLOW_KEY + userId, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //判断当前用户是否关注
        Long userId = UserHolder.getUser().getId();
        Integer count = lambdaQuery()
                .eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result common(Long followUserId) {
        Long id = UserHolder.getUser().getId();
        String key1 = USER_FOLLOW_KEY + id;
        String key2 = USER_FOLLOW_KEY + followUserId;
        Set<String> common = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(common==null || common.isEmpty()){
            //若没有交集
            return Result.ok(Collections.emptyList());
        }
        //如果有交集,从用户id得到UserDTO链表
        List<Long> idList = common.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOList = iUserService.listByIds(idList).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOList);
    }


}
