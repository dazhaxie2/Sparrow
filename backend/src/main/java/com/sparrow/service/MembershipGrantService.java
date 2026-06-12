package com.sparrow.service;

/**
 * Module boundary for membership mutations.
 * Phase 2 can replace this implementation with an RPC call to the user service.
 */
public interface MembershipGrantService {

    void grantMembership(Long userId, int days);
}
