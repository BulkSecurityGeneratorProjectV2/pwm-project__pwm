/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2020 The PWM Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package password.pwm.svc.event;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Value;
import password.pwm.bean.DomainID;

import java.io.Serializable;
import java.time.Instant;

@Value
@Builder( access = AccessLevel.PACKAGE, toBuilder = true )
public class AuditRecordData implements AuditRecord, SystemAuditRecord, UserAuditRecord, HelpdeskAuditRecord, Serializable
{
    private final AuditEventType type;
    private final AuditEvent eventCode;
    private final String guid;
    private final Instant timestamp;
    private final String message;
    private final String narrative;
    private final String xdasTaxonomy;
    private final String xdasOutcome;
    private final String instance;
    private final String perpetratorID;
    private final String perpetratorDN;
    private final String perpetratorLdapProfile;
    private final String sourceAddress;
    private final String sourceHost;
    private final String targetID;
    private final String targetDN;
    private final String targetLdapProfile;
    private final DomainID domain;
}
