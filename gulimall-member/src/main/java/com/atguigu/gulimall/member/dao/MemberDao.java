package com.atguigu.gulimall.member.dao;

import com.atguigu.gulimall.member.entity.MemberEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会员
 * 
 * @author chaobk
 * @email 1004945427@qq.com
 * @date 2023-03-19 11:05:37
 */
@Mapper
public interface MemberDao extends BaseMapper<MemberEntity> {
	
}
