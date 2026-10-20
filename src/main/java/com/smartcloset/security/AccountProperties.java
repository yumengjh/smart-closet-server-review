package com.smartcloset.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Registration mode and abuse limits (docs/design/2026-10-19-用户账号：注册、资料与管理设计.md 第十一节). */
@ConfigurationProperties("closet.account")
public record AccountProperties(Registration registration, Integer loginMaxFailures, Integer loginMaxFailuresPerAddress,
        Integer registerPerAddressHourly) {
    public enum Registration { CLOSED, INVITE, OPEN }
    public AccountProperties {
        if (registration == null) registration = Registration.INVITE;
        if (loginMaxFailures == null) loginMaxFailures = 5;
        if (loginMaxFailuresPerAddress == null) loginMaxFailuresPerAddress = 20;
        if (registerPerAddressHourly == null) registerPerAddressHourly = 5;
    }
}
