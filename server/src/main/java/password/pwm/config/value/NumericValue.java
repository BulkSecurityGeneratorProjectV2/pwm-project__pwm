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
import password.pwm.config.PwmSetting;
import password.pwm.config.PwmSettingProperty;
import password.pwm.config.stored.StoredConfigXmlConstants;
import password.pwm.config.stored.XmlOutputProcessData;
import password.pwm.util.json.JsonFactory;
import password.pwm.util.secure.PwmSecurityKey;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class NumericValue extends AbstractValue implements StoredValue
{
    private final long value;

    public NumericValue( final long value )
    {
        this.value = value;
    }

    public static StoredValueFactory factory( )
    {
        return new StoredValueFactory()
        {
            @Override
            public NumericValue fromJson( final PwmSetting pwmSetting, final String value )
            {
                return new NumericValue( JsonFactory.get().deserialize( value, Long.class ) );
            }

            @Override
            public NumericValue fromXmlElement( final PwmSetting pwmSetting, final XmlElement settingElement, final PwmSecurityKey input )
            {
                final Optional<XmlElement> valueElement = settingElement.getChild( StoredConfigXmlConstants.XML_ELEMENT_VALUE );
                if ( valueElement.isPresent() )
                {
                    final Optional<String> value = valueElement.get().getText();
                    if ( value.isPresent() )
                    {
                        return new NumericValue( normalizeValue( pwmSetting, Long.parseLong( value.get() ) ) );
                    }
                }

                return new NumericValue( 0 );
            }
        };
    }

    private static long normalizeValue( final PwmSetting pwmSetting, final long value )
    {
        final long minValue = Long.parseLong( pwmSetting.getProperties().getOrDefault( PwmSettingProperty.Minimum, "0" ) );
        final long maxValue = Long.parseLong( pwmSetting.getProperties().getOrDefault( PwmSettingProperty.Maximum, "0" ) );

        if ( minValue > 0 && value < minValue )
        {
            return minValue;
        }

        if ( maxValue > 0 && value > maxValue )
        {
            return maxValue;
        }

        return value;
    }

    @Override
    public List<XmlElement> toXmlValues( final String valueElementName, final XmlOutputProcessData xmlOutputProcessData )
    {
        final XmlElement valueElement = XmlChai.getFactory().newElement( valueElementName );
        valueElement.setText( Long.toString( value ) );
        return Collections.singletonList( valueElement );
    }

    @Override
    public Object toNativeObject( )
    {
        return value;
    }

    @Override
    public List<String> validateValue( final PwmSetting pwmSetting )
    {
        return Collections.emptyList();
    }
}
