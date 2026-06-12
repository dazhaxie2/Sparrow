package com.sparrow.user.api;

/**
 * 模块间接口:graph/ai/trade 模块只依赖此接口,不直接依赖 user 模块内部实现。
 * Phase 2 拆服务时,此接口的实现将变成对 user 服务的 RPC 调用。
 */
public interface MembershipService {

    boolean isMember(Long userId);
}
