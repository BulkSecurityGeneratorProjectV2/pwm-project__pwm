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

package password.pwm.config.value;

import org.jrivard.xmlchai.XmlChai;
import org.jrivard.xmlchai.XmlElement;
import password.pwm.PwmConstants;
import password.pwm.config.PwmSetting;
import password.pwm.config.stored.StoredConfigXmlConstants;
import password.pwm.config.stored.XmlOutputProcessData;
import password.pwm.util.i18n.LocaleComparators;
import password.pwm.util.i18n.LocaleHelper;
import password.pwm.util.java.CollectionUtil;
import password.pwm.util.json.JsonFactory;
import password.pwm.util.secure.PwmSecurityKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LocalizedStringValue extends AbstractValue implements StoredValue
{
    private final Map<String, String> value;

    private static final Comparator<String> COMPARATOR = LocaleComparators.stringLocaleComparator( PwmConstants.DEFAULT_LOCALE, LocaleComparators.Flag.DefaultFirst );

    public LocalizedStringValue( final Map<String, String> values )
    {
        if ( CollectionUtil.isEmpty( values ) )
        {
            this.value = Collections.emptyMap();
        }
        else
        {
            final SortedMap<String, String> tempMap = new TreeMap<>( COMPARATOR );
            tempMap.putAll( CollectionUtil.stripNulls( values ) );
            this.value = Collections.unmodifiableMap( tempMap );
        }
    }

    public static StoredValueFactory factory( )
    {
        return new StoredValueFactory()
        {
            @Override
            public LocalizedStringValue fromJson( final PwmSetting pwmSetting, final String input )
            {
                if ( input == null )
                {
                    return new LocalizedStringValue( Collections.emptyMap() );
                }
                else
                {
                    Map<String, String> srcMap = JsonFactory.get().deserializeStringMap( input );
                    srcMap = srcMap == null ? Collections.emptyMap() : new TreeMap<>( srcMap );
                    return new LocalizedStringValue( Collections.unmodifiableMap( srcMap ) );
                }
            }

            @Override
            public LocalizedStringValue fromXmlElement( final PwmSetting pwmSetting, final XmlElement settingElement, final PwmSecurityKey key )
            {
                final List<XmlElement> elements = settingElement.getChildren( StoredConfigXmlConstants.XML_ELEMENT_VALUE );
                final Map<String, String> values = new TreeMap<>();
                for ( final XmlElement loopValueElement : elements )
                {
                    final String localeString = loopValueElement.getAttribute( StoredConfigXmlConstants.XML_ATTRIBUTE_LOCALE ).orElse( "" );
                    loopValueElement.getText().ifPresent( value -> values.put( localeString, value ) );
                }
                return new LocalizedStringValue( values );
            }
        };
    }

    @Override
    public List<XmlElement> toXmlValues( final String valueElementName, final XmlOutputProcessData xmlOutputProcessData )
    {
        final List<XmlElement> returnList = new ArrayList<>( value.size() );
        for ( final Map.Entry<String, String> entry : value.entrySet() )
        {
            final String locale = entry.getKey();
            final String loopValue = entry.getValue();
            final XmlElement valueElement = XmlChai.getFactory().newElement( valueElementName );
            valueElement.setText( loopValue );
            if ( locale != null && locale.length() > 0 )
            {
                valueElement.setAttribute( "locale", locale );
            }
            returnList.add( valueElement );
        }
        return returnList;
    }

    @Override
    public Map<String, String> toNativeObject( )
    {
        return value;
    }

    @Override
    public List<String> validateValue( final PwmSetting pwmSetting )
    {
        if ( pwmSetting.isRequired() )
        {
            if ( value == null || value.size() < 1 || value.values().iterator().next().length() < 1 )
            {
                return Collections.singletonList( "required value missing" );
            }
        }

        final Pattern pattern = pwmSetting.getRegExPattern();
        for ( final String loopValue : value.values() )
        {
            final Matcher matcher = pattern.matcher( loopValue );
            if ( loopValue.length() > 0 && !matcher.matches() )
            {
                return Collections.singletonList( "incorrect value format for value '" + loopValue + "'" );
            }
        }

        return Collections.emptyList();
    }

    @Override
    public String toDebugString( final Locale locale )
    {
        final StringBuilder sb = new StringBuilder();
        for ( final Map.Entry<String, String> entry : value.entrySet() )
        {
            final String localeKey = entry.getKey();
            if ( value.size() > 1 )
            {
                sb.append( "Locale: " ).append( LocaleHelper.debugLabel( LocaleHelper.parseLocaleString( localeKey ) ) ).append( '\n' );
            }
            sb.append( ' ' ).append( entry.getValue() ).append( '\n' );
        }
        return sb.toString();
    }
}
