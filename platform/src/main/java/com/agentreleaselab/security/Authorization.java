package com.agentreleaselab.security;

import com.agentreleaselab.service.ApiException;

/** Scope-or-role authorization gates. Controllers and services call these
 *  before performing privileged operations. OIDC scopes are accepted as an
 *  alternative to roles; API-key callers are unaffected (their scope set is
 *  empty, so the pre-existing role checks decide). */
public final class Authorization {

    private Authorization() {}

    public static void requireScopeOrRole(TenantContext ctx, String scope,
                                         String code, String message, String... roles) {
        if (ctx == null || !ctx.hasScopeOrRole(scope, roles)) {
            throw ApiException.forbidden(code, message);
        }
    }
}
