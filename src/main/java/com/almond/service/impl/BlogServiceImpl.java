package com.almond.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.almond.dto.Result;
import com.almond.dto.ScrollResult;
import com.almond.dto.UserDTO;
import com.almond.entity.Blog;
import com.almond.entity.Follow;
import com.almond.entity.User;
import com.almond.mapper.BlogMapper;
import com.almond.service.IBlogService;
import com.almond.service.IFollowService;
import com.almond.service.IUserService;
import com.almond.utils.SystemConstants;
import com.almond.utils.UserHolder;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.almond.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.almond.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBlogUser(blog);
            this.queryBlogUser(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryById(Long id) {
        //查询blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在");
        }
        //查看并设置博客作者
        queryBlogUser(blog);
        // 查询当前blog是否点赞,并设置属性
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        //判断该用户是否点赞 redis中blog对应一个记录点赞用户的set集合
        Long userId = UserHolder.getUser().getId();
        if(userId == null) return; //用户未登录不需要查询
        Long blogId = blog.getId();
        String key = BLOG_LIKED_KEY + blogId;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    //点赞功能
    @Override
    public Result likeBlog(Long blogId) {
        //判断当前用户是否点赞
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.判断该用户是否点赞 redis中blog对应一个记录点赞用户的set集合
        String key = BLOG_LIKED_KEY + blogId;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //注意自动插箱出null的问题
        if(score == null){
            //2.1未点赞
            //在数据库中将博客点赞数+1
            boolean success = update().setSql("liked = liked + 1").eq("id", blogId).update();
            if (success){
                //redis更新到set集合
                stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
            }
        }else{
            //2.2已点赞 取消点赞
            boolean success = update().setSql("liked = liked - 1").eq("id", blogId).update();
            if (success){
                //redis更新到set集合
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long blogId) {
        //查询博客中前五个点赞的人
        String key = BLOG_LIKED_KEY + blogId;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> idList = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        //生成所有id拼接而成的字符串,以逗号分割
        String idStr = StrUtil.join(",", idList);
        //根据idList生成用户DTO对象 where id in(...) order by field(id,...)
        List<UserDTO> userDTOList = userService.query()
                .in("id", idList).last("order by field(id,"+idStr+")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //返回
        return Result.ok(userDTOList);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
        // 保存探店博文
        save(blog);
        // feed流推模式,将发表的blog推送给关注的用户
        // 1.获取粉丝列表
        List<Follow> follows = followService.query().eq("follow_user_id", userId).list();
        // 2.向关注列表推送博客信息
        for (Follow follow:follows) {
            stringRedisTemplate.opsForZSet().add(FEED_KEY + follow.getUserId(),
                            blog.getId().toString(),
                            System.currentTimeMillis()
                    );
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询收件箱,按时间获取推文
        String key = FEED_KEY + userId;
        // ZREVRANGEBYSCORE key Max Min LIMIT offset count 键 查询范围的最大值以及最小值 从<=最大值的第offset个元素开始查 查询数量
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //3.解析数据
        List<Long> idList = new ArrayList<>(typedTuples.size());
        long minTime = 0;int offsetValue = 1; //找出每一页中的最小时间,作为下一页的最大时间;如果该页有2个重复的最小值,则offset为2
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            //获取博客id
            String idStr = tuple.getValue();
            idList.add(Long.valueOf(idStr));
            //获取博客时间以进行排序
            long time = tuple.getScore().longValue();
            if(time == minTime){
                offsetValue++;
            }else{
                minTime = time;
                offsetValue = 1;
            }
        }
        //根据idList查询出博客列表
        String idStrs = StrUtil.join(",", idList);
        List<Blog> blogs = query().in("id", idList).last("order by field(id," + idStrs + ")").list();
        for (Blog blog : blogs) {
            //查看并设置博客作者
            queryBlogUser(blog);
            // 查询当前blog是否点赞,并设置属性
            isBlogLiked(blog);
        }
        //封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(offsetValue);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    private void queryBlogUser(Blog blog) {
        //查询blog关联的用户
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
