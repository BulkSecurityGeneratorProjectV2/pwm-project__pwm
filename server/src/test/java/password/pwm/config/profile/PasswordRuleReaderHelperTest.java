/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2021 The PWM Project
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

package password.pwm.config.profile;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import password.pwm.PwmApplication;
import password.pwm.user.UserInfoBean;
import password.pwm.util.java.FileSystemUtility;
import password.pwm.util.localdb.TestHelper;
import password.pwm.util.macro.MacroRequest;
import password.pwm.util.password.PasswordRuleReaderHelper;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class PasswordRuleReaderHelperTest
{

    @TempDir
    public Path temporaryFolder;

    private MacroRequest makeMacroRequest() throws Exception
    {
        final Map<String, String> userAttributes;
        {
            userAttributes = Map.of(
                    "cn", "fflintstone",
                    "email", "fred@flintstones.tv",
                    "givenName", "Fred",
                    "sn", "Flintstone" );
        }

        final File testFolder = FileSystemUtility.createDirectory( temporaryFolder, "test-makeMacroRequest" );
        final PwmApplication pwmApplication = TestHelper.makeTestPwmApplication( testFolder );

        final UserInfoBean userInfo = UserInfoBean.builder()
                .attributes( userAttributes )
                .userEmailAddress( "fred@flintstones.tv" )
                .username( "fflintstone" )
                .build();

        return  MacroRequest.forUser( pwmApplication, null, userInfo, null );
    }

    private PasswordRuleReaderHelper makeRuleHelper( final boolean enableMacros )
    {
        final Map<String, String> passwordPolicyRules = new HashMap<>( );
        passwordPolicyRules.put( PwmPasswordRule.AllowMacroInRegExSetting.getKey(), Boolean.toString( enableMacros ) );
        return new PasswordRuleReaderHelper( PwmPasswordPolicy.createPwmPasswordPolicy( PwmPasswordPolicy.defaultPolicy().getDomainID(), passwordPolicyRules ) );
    }

    @Test
    public void testReadRegExSettingNoRegex() throws Exception
    {
        final MacroRequest macroRequest = makeMacroRequest();
        final PasswordRuleReaderHelper ruleHelper = makeRuleHelper( true );

        final String input = "@User:ID@, First Name: @LDAP:givenName@, Last Name: @LDAP:sn@, Email: @User:Email@";

        final List<Pattern> patterns = ruleHelper.readRegExSetting( PwmPasswordRule.RegExMatch, macroRequest, input );

        final String expected = "fflintstone, First Name: Fred, Last Name: Flintstone, Email: fred@flintstones.tv";
        Assertions.assertEquals( 1, patterns.size() );
        Assertions.assertEquals( expected, patterns.get( 0 ).pattern() );
    }

    @Test
    public void testReadRegExSetting() throws Exception
    {
        final MacroRequest macroRequest = makeMacroRequest();
        final PasswordRuleReaderHelper ruleHelper = makeRuleHelper( true );

        final String input = "^@User:ID@[0-9]+$;;;^password$";

        final List<Pattern> patterns = ruleHelper.readRegExSetting( PwmPasswordRule.RegExMatch, macroRequest, input );

        Assertions.assertEquals( 2, patterns.size() );
        Assertions.assertEquals( "^fflintstone[0-9]+$", patterns.get( 0 ).pattern() );
        Assertions.assertEquals( "^password$", patterns.get( 1 ).pattern() );
    }
}
