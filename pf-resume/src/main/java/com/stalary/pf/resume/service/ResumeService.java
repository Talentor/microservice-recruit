package com.stalary.pf.resume.service;

import com.alibaba.fastjson.JSONObject;
import com.stalary.lightmqclient.facade.Producer;
import com.stalary.pf.resume.client.RecruitClient;
import com.stalary.pf.resume.client.UserClient;
import com.stalary.pf.resume.data.constant.Constant;
import com.stalary.pf.resume.data.constant.RedisKeys;
import com.stalary.pf.resume.data.dto.*;
import com.stalary.pf.resume.data.entity.ResumeEntity;
import com.stalary.pf.resume.data.entity.SkillEntity;
import com.stalary.pf.resume.data.vo.ReceiveInfo;
import com.stalary.pf.resume.data.vo.SendInfo;
import com.stalary.pf.resume.holder.UserHolder;
import com.stalary.pf.resume.repo.ResumeRepo;
import com.stalary.pf.resume.repo.SkillRepo;
import com.stalary.pf.resume.utils.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ResumeService
 *
 * @author lirongqian
 * @since 2018/04/14
 */
@Service
@Slf4j
public class ResumeService extends BaseService<ResumeEntity, ResumeRepo> {

    public ResumeService(ResumeRepo repo) {
        super(repo);
    }

    @Resource(name = "mongoTemplate")
    private MongoTemplate mongo;

    @Resource(name = "skillRepo")
    private SkillRepo skillRepo;

    @Resource
    private RecruitClient recruitClient;

    @Resource
    private Producer producer;

    @Resource
    private UserClient userClient;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redis;


    /**
     * 处理简历
     */
    public void handleResume(SendResume sendResume) {
        log.info("start handle resume");
        HashOperations<String, String, String> redisHash = redis.opsForHash();
        long start = System.currentTimeMillis();
        Long userId = sendResume.getUserId();
        Long recruitId = sendResume.getRecruitId();
        // 构建发送列表
        String sendKey = Constant.getKey(RedisKeys.RESUME_SEND, String.valueOf(userId));
        redisHash.put(sendKey, String.valueOf(recruitId), JSONObject.toJSONString(sendResume));
        // 构建获取列表
        String receiveKey = Constant.getKey(RedisKeys.RESUME_RECEIVE, String.valueOf(recruitId));
        UserInfo userInfo = userClient.getUserInfo(userId).getData();
        // 计算匹配度
        int rate = calculate(recruitId, userId);
        ReceiveResume receiveResume = new ReceiveResume(sendResume.getTitle(), userInfo.getNickname(), userInfo.getUserId(), rate, LocalDateTime.now());
        redisHash.put(receiveKey, String.valueOf(userId), JSONObject.toJSONString(receiveResume));
        log.info("end handle resume spend time is " + (System.currentTimeMillis() - start));
    }

    /**
     * 保存简历
     */
    public ResumeEntity saveResume(ResumeEntity resume) {
        final long resumeId = IdUtil.getNextId(ResumeEntity.class.getSimpleName(), mongo);
        skillRepo.saveAll(
                resume.getSkills()
                        .stream()
                        .peek(skill -> {
                            // 存入简历id
                            if (skill.getResumeId() == 0) {
                                skill.setResumeId(resumeId);
                            }
                        })
                        .collect(Collectors.toList())
        );
        return repo.save(resume);
    }

    public ResumeEntity findByUserId(Long userId) {
        return repo.findByUserId(userId);
    }

    /**
     * 简历打分
     *
     */
    public int calculate(Long recruitId, Long userId) {
        Recruit recruit = recruitClient.getRecruit(recruitId).getData();
        List<SkillRule> skillRuleList = recruit.getSkillList();
        ResumeEntity resume = repo.findByUserId(userId);
        List<SkillEntity> skillList = resume.getSkills();
        // 求出规则表中总和
        int ruleSum = skillRuleList
                .stream()
                .mapToInt(SkillRule::getWeight)
                .sum();
        // 求出规则表中的技能点
        List<String> nameRuleList = skillRuleList
                .stream()
                .map(SkillRule::getName)
                .collect(Collectors.toList());
        // 求出简历表中的技能点
        List<String> nameList = skillList
                .stream()
                .map(SkillEntity::getName)
                .collect(Collectors.toList());
        // 求出技能点交集
        List<String> intersection = nameRuleList
                .stream()
                .filter(nameList::contains)
                .collect(Collectors.toList());
        // 生成规则表的映射
        Map<String, Integer> nameRuleMap = skillRuleList
                .stream()
                .collect(Collectors.toMap(SkillRule::getName, SkillRule::getWeight));
        // 命中的和
        int getRuleSum = intersection
                .stream()
                .mapToInt(nameRuleMap::get)
                .sum();
        // 规则占比
        double rulePercent = (double) getRuleSum / ruleSum;
        // 技能点总和
        int sum = intersection.size() * 4;
        // 生成技能点的映射
        Map<String, Integer> nameMap = skillList
                .stream()
                .collect(Collectors.toMap(SkillEntity::getName, SkillEntity::getLevel));
        // 命中技能点的和
        int getSum = intersection
                .stream()
                .mapToInt(nameMap::get)
                .sum();
        // 技能点占比
        double percent = (double) getSum / sum;
        return (int) Math.round(percent * rulePercent * 100);
    }

    /**
     * 投递简历
     */
    public void postResume(Long recruitId, String title) {
        Long userId = UserHolder.get();
        String json = JSONObject.toJSONString(new SendResume(userId, recruitId, title, LocalDateTime.now()));
        // 处理简历
        producer.send(Constant.HANDLE_RESUME, json);
        // 向接受方发送通知
        producer.send(Constant.RECEIVE_RESUME, json);
        // 向投递方发送通知
        producer.send(Constant.SEND_RESUME, json);
    }

}