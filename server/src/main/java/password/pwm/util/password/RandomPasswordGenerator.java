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

package password.pwm.util.password;

import com.novell.ldapchai.exception.ImpossiblePasswordPolicyException;
import lombok.Value;
import password.pwm.PwmDomain;
import password.pwm.bean.SessionLabel;
import password.pwm.config.DomainConfig;
import password.pwm.config.PwmSetting;
import password.pwm.config.profile.PwmPasswordPolicy;
import password.pwm.config.profile.PwmPasswordRule;
import password.pwm.error.ErrorInformation;
import password.pwm.error.PwmError;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.svc.stats.Statistic;
import password.pwm.svc.stats.StatisticsClient;
import password.pwm.util.PasswordData;
import password.pwm.util.java.TimeDuration;
import password.pwm.util.logging.PwmLogLevel;
import password.pwm.util.logging.PwmLogger;
import password.pwm.util.secure.PwmRandom;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Random password generator.
 *
 * @author Jason D. Rivard
 */
public class RandomPasswordGenerator
{
    /**
     * Default seed phrases.  Most basic ASCII chars, except those that are visually ambiguous are
     * represented here.  No multi-character phrases are included.
     */
    public static final Set<String> DEFAULT_SEED_PHRASES = Collections.unmodifiableSet( new HashSet<>( Arrays.asList(
            "a", "b", "c", "d", "e", "f", "g", "h", "j", "k", "m", "n", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
            "a", "b", "c", "d", "e", "f", "g", "h", "j", "k", "m", "n", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
            "a", "b", "c", "d", "e", "f", "g", "h", "j", "k", "m", "n", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
            "A", "B", "C", "D", "E", "F", "G", "H", "J", "K", "L", "M", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
            "2", "3", "4", "5", "6", "7", "8", "9",
            "@", "&", "!", "?", "%", "$", "#", "^", ")", "(", "+", "-", "=", ".", ",", "/", "\\"
    ) ) );

    private static final PwmLogger LOGGER = PwmLogger.forClass( RandomPasswordGenerator.class );

    public static PasswordData createRandomPassword(
            final SessionLabel sessionLabel,
            final PwmPasswordPolicy passwordPolicy,
            final PwmDomain pwmDomain
    )
            throws PwmUnrecoverableException
    {
        final RandomGeneratorConfig randomGeneratorConfig = RandomGeneratorConfig.make( pwmDomain, passwordPolicy );

        return createRandomPassword(
                sessionLabel,
                randomGeneratorConfig,
                pwmDomain
        );
    }


    /**
     * <p>Creates a new password that satisfies the password rules.  All rules are checked for.  If for some
     * reason the pwmRandom algorithm can not generate a valid password, null will be returned.</p>
     *
     * <p>If there is an identifiable reason the password can not be created (such as mis-configured rules) then
     * an {@link com.novell.ldapchai.exception.ImpossiblePasswordPolicyException} will be thrown.</p>
     *
     * @param sessionLabel          A valid pwmSession
     * @param randomGeneratorConfig Policy to be used during generation
     * @param pwmDomain        Used to read configuration, seedmanager and other services.
     * @return A randomly generated password value that meets the requirements of this {@code PasswordPolicy}
     * @throws ImpossiblePasswordPolicyException If there is no way to create a password using the configured rules and
     *                                        default seed phrase
     * @throws PwmUnrecoverableException if the operation can not be completed
     */
    public static PasswordData createRandomPassword(
            final SessionLabel sessionLabel,
            final RandomGeneratorConfig randomGeneratorConfig,
            final PwmDomain pwmDomain
    )
            throws PwmUnrecoverableException
    {
        final Instant startTime = Instant.now();

        randomGeneratorConfig.validateSettings( pwmDomain );

        final PwmRandom pwmRandom = pwmDomain.getSecureService().pwmRandom();
        final SeedMachine seedMachine = new SeedMachine( pwmRandom, normalizeSeeds( randomGeneratorConfig.getSeedlistPhrases() ) );

        // determine the password policy to use for random generation
        final PwmPasswordPolicy randomGenPolicy = makeRandomGenPwdPolicy( randomGeneratorConfig, pwmDomain );

        // read a rule validator
        // modify until it passes all the rules
        final MutatorResult mutatorResult = passwordMutator( sessionLabel, pwmDomain, seedMachine, randomGeneratorConfig, randomGenPolicy );

        // report outcome

        if ( mutatorResult.isValidPassword() )
        {
            LOGGER.trace( sessionLabel, () -> "finished random password generation after " + mutatorResult.getRounds()
                    + " rounds.", TimeDuration.fromCurrent( startTime ) );
        }
        else
        {
            if ( LOGGER.isInterestingLevel( PwmLogLevel.ERROR ) )
            {
                final PwmPasswordRuleValidator pwmPasswordRuleValidator = PwmPasswordRuleValidator.create( sessionLabel, pwmDomain, randomGenPolicy );
                final int errors = pwmPasswordRuleValidator.internalPwmPolicyValidator( mutatorResult.getPassword(), null, null ).size();
                final int judgeLevel = PasswordUtility.judgePasswordStrength( pwmDomain.getConfig(), mutatorResult.getPassword() );
                LOGGER.error( sessionLabel, () -> "failed random password generation after "
                                + mutatorResult.getRounds() + " rounds. " + "(errors=" + errors + ", judgeLevel=" + judgeLevel,
                        TimeDuration.fromCurrent( startTime ) );
            }
        }

        StatisticsClient.incrementStat( pwmDomain, Statistic.GENERATED_PASSWORDS );

        LOGGER.trace( sessionLabel, () -> "real-time random password generator called"
                + " (" + TimeDuration.compactFromCurrent( startTime ) + ")" );

        return new PasswordData( mutatorResult.getPassword() );
    }

    @Value
    private static class MutatorResult
    {
        private final String password;
        private final boolean validPassword;
        private final int rounds;
    }

    private static PwmPasswordPolicy makeRandomGenPwdPolicy(
            final RandomGeneratorConfig effectiveConfig,
            final PwmDomain pwmDomain
    )
    {
        final PwmPasswordPolicy defaultPolicy = PwmPasswordPolicy.defaultPolicy();
        final Map<String, String> newPolicyMap = new HashMap<>( defaultPolicy.getPolicyMap() );

        newPolicyMap.put( PwmPasswordRule.MaximumLength.getKey(), String.valueOf( effectiveConfig.getMaximumLength() ) );
        if ( effectiveConfig.getMinimumLength() > defaultPolicy.getRuleHelper().readIntValue( PwmPasswordRule.MinimumLength ) )
        {
            newPolicyMap.put( PwmPasswordRule.MinimumLength.getKey(), String.valueOf( effectiveConfig.getMinimumLength() ) );
        }
        if ( effectiveConfig.getMaximumLength() < defaultPolicy.getRuleHelper().readIntValue( PwmPasswordRule.MaximumLength ) )
        {
            newPolicyMap.put( PwmPasswordRule.MaximumLength.getKey(), String.valueOf( effectiveConfig.getMaximumLength() ) );
        }
        if ( effectiveConfig.getMinimumStrength() > defaultPolicy.getRuleHelper().readIntValue( PwmPasswordRule.MinimumStrength ) )
        {
            newPolicyMap.put( PwmPasswordRule.MinimumStrength.getKey(), String.valueOf( effectiveConfig.getMinimumStrength() ) );
        }
        return  PwmPasswordPolicy.createPwmPasswordPolicy( pwmDomain.getDomainID(), newPolicyMap );
    }

    private static MutatorResult passwordMutator(
            final SessionLabel sessionLabel,
            final PwmDomain pwmDomain,
            final SeedMachine seedMachine,
            final RandomGeneratorConfig effectiveConfig,
            final PwmPasswordPolicy randomGenPolicy

    )
            throws PwmUnrecoverableException
    {

        final int maxTryCount = effectiveConfig.getMaximumAttempts();
        final int jitterCount = effectiveConfig.getJitter();
        final PwmRandom pwmRandom = pwmDomain.getSecureService().pwmRandom();

        final StringBuilder password = new StringBuilder();
        password.append( generateNewPassword( pwmRandom, seedMachine, effectiveConfig.getMinimumLength() ) );


        final PwmPasswordRuleValidator pwmPasswordRuleValidator = PwmPasswordRuleValidator.create(
                sessionLabel, pwmDomain, randomGenPolicy, PwmPasswordRuleValidator.Flag.FailFast );

        int tryCount = 0;
        boolean validPassword = false;
        while ( !validPassword && tryCount < maxTryCount )
        {
            tryCount++;
            validPassword = true;

            if ( tryCount % jitterCount == 0 )
            {
                password.delete( 0, password.length() );
                password.append( generateNewPassword( pwmRandom, seedMachine, effectiveConfig.getMinimumLength() ) );
            }

            final List<ErrorInformation> errors = pwmPasswordRuleValidator.internalPwmPolicyValidator(
                    password.toString(), null, null );
            if ( errors != null && !errors.isEmpty() )
            {
                validPassword = false;
                modifyPasswordBasedOnErrors( pwmRandom, password, errors, seedMachine );
            }
            else if ( checkPasswordAgainstDisallowedHttpValues( pwmDomain.getConfig(), password.toString() ) )
            {
                validPassword = false;
                password.delete( 0, password.length() );
                password.append( generateNewPassword( pwmRandom, seedMachine, effectiveConfig.getMinimumLength() ) );
            }
        }

        return new MutatorResult( password.toString(), validPassword, tryCount );
    }

    private static void modifyPasswordBasedOnErrors(
            final PwmRandom pwmRandom,
            final StringBuilder password,
            final List<ErrorInformation> errors,
            final SeedMachine seedMachine
    )
    {
        if ( password == null || errors == null || errors.isEmpty() )
        {
            return;
        }

        final Set<PwmError> errorMessages = EnumSet.noneOf( PwmError.class );
        for ( final ErrorInformation errorInfo : errors )
        {
            errorMessages.add( errorInfo.getError() );
        }

        boolean touched = false;

        if ( errorMessages.contains( PwmError.PASSWORD_TOO_SHORT ) )
        {
            addRandChar( pwmRandom, password, seedMachine.getAllChars() );
            touched = true;
        }

        if ( errorMessages.contains( PwmError.PASSWORD_TOO_LONG ) )
        {
            password.deleteCharAt( pwmRandom.nextInt( password.length() ) );
            touched = true;
        }

        if ( errorMessages.contains( PwmError.PASSWORD_FIRST_IS_NUMERIC ) || errorMessages.contains( PwmError.PASSWORD_FIRST_IS_SPECIAL ) )
        {
            password.deleteCharAt( 0 );
            touched = true;
        }

        if ( errorMessages.contains( PwmError.PASSWORD_LAST_IS_NUMERIC ) || errorMessages.contains( PwmError.PASSWORD_LAST_IS_SPECIAL ) )
        {
            password.deleteCharAt( password.length() - 1 );
            touched = true;
        }

        if ( errorMessages.contains( PwmError.PASSWORD_NOT_ENOUGH_NUM ) )
        {
            addRandChar( pwmRandom, password, seedMachine.getNumChars() );
            touched = true;
        }

        if ( errorMessages.contains( PwmError.PASSWORD_NOT_ENOUGH_SPECIAL ) )
        {
            addRandChar( pwmRandom, password, seedMachine.getSpecialChars() );
            touched = true;
        }

        if ( errorMessages.contains( PwmError.PASSWORD_NOT_ENOUGH_UPPER ) )
        {
            addRandChar( pwmRandom, password, seedMachine.getUpperChars() );
            touched = true;
        }

        if ( errorMessages.contains( PwmError.PASSWORD_NOT_ENOUGH_LOWER ) )
        {
            addRandChar( pwmRandom, password, seedMachine.getLowerChars() );
            touched = true;
        }

        PasswordCharCounter passwordCharCounter = new PasswordCharCounter( password.toString() );
        if ( errorMessages.contains( PwmError.PASSWORD_TOO_MANY_NUMERIC ) && passwordCharCounter.getNumericCharCount() > 0 )
        {
            deleteRandChar( pwmRandom, password, passwordCharCounter.getNumericChars() );
            touched = true;
            passwordCharCounter = new PasswordCharCounter( password.toString() );
        }

        if ( errorMessages.contains( PwmError.PASSWORD_TOO_MANY_SPECIAL ) && passwordCharCounter.getSpecialCharsCount() > 0 )
        {
            deleteRandChar( pwmRandom, password, passwordCharCounter.getSpecialChars() );
            touched = true;
            passwordCharCounter = new PasswordCharCounter( password.toString() );
        }

        if ( errorMessages.contains( PwmError.PASSWORD_TOO_MANY_UPPER ) && passwordCharCounter.getUpperCharCount() > 0 )
        {
            deleteRandChar( pwmRandom, password, passwordCharCounter.getUpperChars() );
            touched = true;
            passwordCharCounter = new PasswordCharCounter( password.toString() );
        }

        if ( errorMessages.contains( PwmError.PASSWORD_TOO_MANY_LOWER ) && passwordCharCounter.getLowerCharCount() > 0 )
        {
            deleteRandChar( pwmRandom, password, passwordCharCounter.getLowerChars() );
            touched = true;
        }

        if ( errorMessages.contains( PwmError.PASSWORD_TOO_WEAK ) )
        {
            randomPasswordModifier( pwmRandom, password, seedMachine );
            touched = true;
        }

        if ( !touched )
        {
            // dunno whats wrong, try just deleting a pwmRandom char, and hope a re-insert will add another.
            randomPasswordModifier( pwmRandom, password, seedMachine );
        }
    }

    private static void deleteRandChar(
            final PwmRandom pwmRandom,
            final StringBuilder password,
            final String charsToRemove
    )
            throws ImpossiblePasswordPolicyException
    {
        final List<Integer> removePossibilities = new ArrayList<>();
        for ( int i = 0; i < password.length(); i++ )
        {
            final char loopChar = password.charAt( i );
            final int index = charsToRemove.indexOf( loopChar );
            if ( index != -1 )
            {
                removePossibilities.add( i );
            }
        }
        if ( removePossibilities.isEmpty() )
        {
            throw new ImpossiblePasswordPolicyException( ImpossiblePasswordPolicyException.ErrorEnum.UNEXPECTED_ERROR );
        }
        final Integer charToDelete = removePossibilities.get( pwmRandom.nextInt( removePossibilities.size() ) );
        password.deleteCharAt( charToDelete );
    }

    private static void randomPasswordModifier(
            final PwmRandom pwmRandom,
            final StringBuilder password,
            final SeedMachine seedMachine
    )
    {
        switch ( pwmRandom.nextInt( 6 ) )
        {
            case 0:
            case 1:
                addRandChar( pwmRandom, password, seedMachine.getSpecialChars() );
                break;
            case 2:
            case 3:
                addRandChar( pwmRandom, password, seedMachine.getNumChars() );
                break;
            case 4:
                addRandChar( pwmRandom, password, seedMachine.getUpperChars() );
                break;
            case 5:
                addRandChar( pwmRandom, password, seedMachine.getLowerChars() );
                break;
            default:
                switchRandomCase( pwmRandom, password );
                break;
        }
    }

    private static void switchRandomCase(
            final PwmRandom pwmRandom,
            final StringBuilder password
    )
    {
        for ( int i = 0; i < password.length(); i++ )
        {
            final int randspot = pwmRandom.nextInt( password.length() );
            final char oldChar = password.charAt( randspot );
            if ( Character.isLetter( oldChar ) )
            {
                final char newChar = Character.isUpperCase( oldChar ) ? Character.toLowerCase( oldChar ) : Character.toUpperCase( oldChar );
                password.deleteCharAt( randspot );
                password.insert( randspot, newChar );
                return;
            }
        }
    }

    private static void addRandChar( final PwmRandom pwmRandom, final StringBuilder password, final String allowedChars )
            throws ImpossiblePasswordPolicyException
    {
        final int insertPosition = password.length() < 1 ? 0 : pwmRandom.nextInt( password.length() );
        addRandChar( pwmRandom, password, allowedChars, insertPosition );
    }

    private static void addRandChar( final PwmRandom pwmRandom, final StringBuilder password, final String allowedChars, final int insertPosition )
            throws ImpossiblePasswordPolicyException
    {
        if ( allowedChars.length() < 1 )
        {
            throw new ImpossiblePasswordPolicyException( ImpossiblePasswordPolicyException.ErrorEnum.REQUIRED_CHAR_NOT_ALLOWED );
        }
        else
        {
            final int newCharPosition = pwmRandom.nextInt( allowedChars.length() );
            final char charToAdd = allowedChars.charAt( newCharPosition );
            password.insert( insertPosition, charToAdd );
        }
    }

    private static boolean checkPasswordAgainstDisallowedHttpValues( final DomainConfig config, final String password )
    {
        if ( config != null && password != null )
        {
            final List<String> disallowedInputs = config.getAppConfig().readSettingAsStringArray( PwmSetting.DISALLOWED_HTTP_INPUTS );
            for ( final String loopRegex : disallowedInputs )
            {
                if ( password.matches( loopRegex ) )
                {
                    return true;
                }
            }
        }
        return false;
    }

    private RandomPasswordGenerator( )
    {
    }

    protected static class SeedMachine
    {
        private final Collection<String> seeds;
        private final PwmRandom pwmRandom;

        private String allChars;
        private String numChars;
        private String specialChars;
        private String upperChars;
        private String lowerChars;

        public SeedMachine( final PwmRandom pwmRandom, final Collection<String> seeds )
        {
            this.pwmRandom = pwmRandom;
            this.seeds = seeds;
        }

        public String getRandomSeed( )
        {
            return new ArrayList<>( seeds ).get( pwmRandom.nextInt( seeds.size() ) );
        }

        public String getAllChars( )
        {
            if ( allChars == null )
            {
                final StringBuilder sb = new StringBuilder();
                for ( final String s : seeds )
                {
                    for ( final Character c : s.toCharArray() )
                    {
                        if ( sb.indexOf( c.toString() ) == -1 )
                        {
                            sb.append( c );
                        }
                    }
                }
                allChars = sb.length() > 2 ? sb.toString() : ( new SeedMachine( pwmRandom, DEFAULT_SEED_PHRASES ) ).getAllChars();
            }

            return allChars;
        }

        public String getNumChars( )
        {
            if ( numChars == null )
            {
                final StringBuilder sb = new StringBuilder();
                for ( final Character c : getAllChars().toCharArray() )
                {
                    if ( Character.isDigit( c ) )
                    {
                        sb.append( c );
                    }
                }
                numChars = sb.length() > 2 ? sb.toString() : ( new SeedMachine( pwmRandom, DEFAULT_SEED_PHRASES ) ).getNumChars();
            }

            return numChars;
        }

        public String getSpecialChars( )
        {
            if ( specialChars == null )
            {
                final StringBuilder sb = new StringBuilder();
                for ( final Character c : getAllChars().toCharArray() )
                {
                    if ( !Character.isLetterOrDigit( c ) )
                    {
                        sb.append( c );
                    }
                }
                specialChars = sb.length() > 2 ? sb.toString() : ( new SeedMachine( pwmRandom, DEFAULT_SEED_PHRASES ) ).getSpecialChars();
            }

            return specialChars;
        }

        public String getUpperChars( )
        {
            if ( upperChars == null )
            {
                final StringBuilder sb = new StringBuilder();
                for ( final Character c : getAllChars().toCharArray() )
                {
                    if ( Character.isUpperCase( c ) )
                    {
                        sb.append( c );
                    }
                }
                upperChars = sb.length() > 0 ? sb.toString() : ( new SeedMachine( pwmRandom, DEFAULT_SEED_PHRASES ) ).getUpperChars();
            }
            return upperChars;
        }

        public String getLowerChars( )
        {
            if ( lowerChars == null )
            {
                final StringBuilder sb = new StringBuilder();
                for ( final Character c : getAllChars().toCharArray() )
                {
                    if ( Character.isLowerCase( c ) )
                    {
                        sb.append( c );
                    }
                }
                lowerChars = sb.length() > 0 ? sb.toString() : ( new SeedMachine( pwmRandom, DEFAULT_SEED_PHRASES ) ).getLowerChars();
            }

            return lowerChars;
        }
    }

    private static String generateNewPassword( final PwmRandom pwmRandom, final SeedMachine seedMachine, final int desiredLength )
    {
        final StringBuilder password = new StringBuilder();

        while ( password.length() < ( desiredLength - 1 ) )
        {
            //loop around until we're long enough
            password.append( seedMachine.getRandomSeed() );
        }

        if ( pwmRandom.nextInt( 3 ) == 0 )
        {
            final SeedMachine defaultSeedMachine = new SeedMachine( pwmRandom, DEFAULT_SEED_PHRASES );
            addRandChar( pwmRandom, password, defaultSeedMachine.getNumChars(), pwmRandom.nextInt( password.length() ) );
        }

        if ( pwmRandom.nextBoolean() )
        {
            switchRandomCase( pwmRandom, password );
        }

        return password.toString();
    }

    private static Collection<String> normalizeSeeds( final Collection<String> inputSeeds )
    {
        if ( inputSeeds == null )
        {
            return DEFAULT_SEED_PHRASES;
        }

        final Collection<String> newSeeds = new HashSet<>( inputSeeds );
        newSeeds.removeIf( s -> s == null || s.length() < 1 );

        return newSeeds.isEmpty() ? DEFAULT_SEED_PHRASES : newSeeds;
    }

}
