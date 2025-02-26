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

package password.pwm.util;

import lombok.Builder;
import lombok.Value;
import org.apache.commons.text.WordUtils;
import password.pwm.AppProperty;
import password.pwm.PwmApplication;
import password.pwm.PwmDomain;
import password.pwm.PwmConstants;
import password.pwm.bean.EmailItemBean;
import password.pwm.config.DomainConfig;
import password.pwm.config.PwmSetting;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.health.HealthRecord;
import password.pwm.i18n.Display;
import password.pwm.svc.PwmService;
import password.pwm.svc.report.ReportSummaryData;
import password.pwm.util.java.CollectionUtil;
import password.pwm.util.java.PwmTimeUtil;
import password.pwm.util.java.StringUtil;
import password.pwm.util.java.TimeDuration;
import password.pwm.util.logging.PwmLogger;
import password.pwm.util.macro.MacroRequest;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class DailySummaryJob implements Runnable
{
    private static final PwmLogger LOGGER = PwmLogger.forClass( DailySummaryJob.class );

    private final PwmApplication pwmApplication;

    public DailySummaryJob( final PwmApplication pwmDomain )
    {
        this.pwmApplication = pwmDomain;
    }

    @Value
    @Builder
    static class DailySummaryJobSettings
    {
        private final boolean reportingEnableDailyJob;
        private final boolean dailySummaryJobsEnabled;
        private final List<String> toAddress;
        private final String fromAddress;
        private final String siteUrl;

        static DailySummaryJobSettings fromConfig( final DomainConfig config )
        {
            return DailySummaryJobSettings.builder()
                    .dailySummaryJobsEnabled( config.getAppConfig().readSettingAsBoolean( PwmSetting.EVENTS_ALERT_DAILY_SUMMARY ) )
                    .toAddress( config.getAppConfig().readSettingAsStringArray( PwmSetting.AUDIT_EMAIL_SYSTEM_TO ) )
                    .fromAddress( config.getAppConfig().readAppProperty( AppProperty.AUDIT_EVENTS_EMAILFROM ) )
                    .siteUrl( config.getAppConfig().readSettingAsString( PwmSetting.PWM_SITE_URL ) )
                    .reportingEnableDailyJob( config.getAppConfig().readSettingAsBoolean( PwmSetting.REPORTING_ENABLE_DAILY_JOB ) )
                    .build();
        }
    }

    @Override
    public void run()
    {
        for ( final PwmDomain pwmDomain : pwmApplication.domains().values() )
        {
            final DailySummaryJobSettings dailySummaryJobSettings = DailySummaryJobSettings.fromConfig( pwmDomain.getConfig() );
            try
            {
                alertDailyStats( pwmDomain, dailySummaryJobSettings );
            }
            catch ( final Exception e )
            {
                LOGGER.error( () -> "error while generating daily alert statistics: " + e.getMessage() );
            }
    }
    }

    private static void alertDailyStats(
        final PwmDomain pwmDomain,
        final DailySummaryJobSettings settings
    )
            throws PwmUnrecoverableException
    {
        if ( !checkIfEnabled( pwmDomain, settings ) )
        {
            LOGGER.trace( () -> "skipping daily summary alert job, setting "
                    + PwmSetting.EVENTS_ALERT_DAILY_SUMMARY.toMenuLocationDebug( null, PwmConstants.DEFAULT_LOCALE )
                    + " not configured" );
            return;
        }

        if ( pwmDomain.getStatisticsManager().status() != PwmService.STATUS.OPEN )
        {
            LOGGER.debug( () -> "skipping daily summary alert job, statistics service is not open" );
            return;
        }

        final Map<String, String> dailyStatistics = pwmDomain.getStatisticsManager().dailyStatisticsAsLabelValueMap();

        final Locale locale = PwmConstants.DEFAULT_LOCALE;

        for ( final String toAddress : settings.getToAddress() )
        {
            final String fromAddress = settings.getFromAddress();
            final String subject = Display.getLocalizedMessage( locale, Display.Title_Application, pwmDomain.getConfig() ) + " - Daily Summary";
            final StringBuilder textBody = new StringBuilder();
            final StringBuilder htmlBody = new StringBuilder();
            makeEmailBody( pwmDomain, settings, dailyStatistics, locale, textBody, htmlBody );
            final EmailItemBean emailItem = new EmailItemBean( toAddress, fromAddress, subject, textBody.toString(), htmlBody.toString() );
            LOGGER.debug( () -> "sending daily summary email to " + toAddress );
            pwmDomain.getPwmApplication().getEmailQueue().submitEmail( emailItem, null, MacroRequest.forNonUserSpecific( pwmDomain.getPwmApplication(), null ) );
        }
    }

    private static void makeEmailBody(
            final PwmDomain pwmDomain,
            final DailySummaryJobSettings settings,
            final Map<String, String> dailyStatistics,
            final Locale locale,
            final StringBuilder textBody,
            final StringBuilder htmlBody )
    {
        htmlBody.append( htmlHeader() );

        {
            // server info
            final Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put( "Instance ID", pwmDomain.getPwmApplication().getInstanceID() );
            metadata.put( "Site URL", settings.getSiteUrl() );
            metadata.put( "Timestamp", StringUtil.toIsoDate( Instant.now() ) );
            metadata.put( "Up Time", PwmTimeUtil.asLongString( TimeDuration.fromCurrent( pwmDomain.getPwmApplication().getStartupTime() ) ) );

            for ( final Map.Entry<String, String> entry : metadata.entrySet() )
            {
                final String key = entry.getKey();
                final String value = entry.getValue();
                htmlBody.append( key ).append( ": " ).append( value ).append( "<br/>" );
                textBody.append( key ).append( ": " ).append( value ).append( '\n' );
            }
        }

        textBody.append( '\n' );
        htmlBody.append( "<br/>" );

        {
            // health check data
            final Collection<HealthRecord> healthRecords = pwmDomain.getPwmApplication().getHealthMonitor().getHealthRecords();
            textBody.append( "-- Health Check Results --\n" );
            htmlBody.append( "<h2>Health Check Results</h2>" );

            htmlBody.append( "<table border='1'>" );
            for ( final HealthRecord record : healthRecords )
            {
                htmlBody.append( "<tr><td class='key'>" ).append( record.getTopic( PwmConstants.DEFAULT_LOCALE, pwmDomain.getConfig() ) ).append( "</td>" );

                {
                    final String color;
                    switch ( record.getStatus() )
                    {
                        case GOOD:
                            color = "#8ced3f";
                            break;
                        case CAUTION:
                            color = "#FFCD59";
                            break;
                        case WARN:
                            color = "#d20734";
                            break;
                        default:
                            color = "white";
                    }
                    htmlBody.append( "<td bgcolor='" ).append( color ).append( "'>" ).append( record.getStatus() ).append( "</td>" );
                }

                htmlBody.append( "<td>" ).append( record.getDetail( PwmConstants.DEFAULT_LOCALE, pwmDomain.getConfig() ) ).append( "</td></tr>" );
            }
            htmlBody.append( "</table>" );


            final int wrapLineLength = 120;
            for ( final HealthRecord record : healthRecords )
            {
                {
                    final String wrappedLine = wrapText( record.getStatus().getDescription( locale,
                            pwmDomain.getConfig() ) + ": " + record.getTopic( PwmConstants.DEFAULT_LOCALE,
                            pwmDomain.getConfig() ) + " - " + stripHtmlTags(
                            record.getDetail( PwmConstants.DEFAULT_LOCALE, pwmDomain.getConfig() ) ), wrapLineLength );
                    textBody.append( wrappedLine ).append( '\n' );
                }
            }
        }

        textBody.append( '\n' );
        htmlBody.append( "<br/>" );

        if ( settings.isReportingEnableDailyJob() )
        {
            final List<ReportSummaryData.PresentationRow> summaryData = pwmDomain.getPwmApplication().getReportService()
                    .getSummaryData().asPresentableCollection( pwmDomain.getPwmApplication().getConfig(), locale );

            textBody.append( "-- Directory Report Summary --\n" );
            for ( final ReportSummaryData.PresentationRow record : summaryData )
            {
                textBody.append( record.getLabel() ).append( ": " ).append( record.getCount() );
                if ( record.getPct() != null && !record.getPct().isEmpty() )
                {
                    textBody.append( " (" ).append( record.getPct() ).append( ')' );
                }
                textBody.append( '\n' );
            }

            htmlBody.append( "<h2>Directory Report Summary</h2>" );
            htmlBody.append( "<table border='1'>" );
            for ( final ReportSummaryData.PresentationRow record : summaryData )
            {
                htmlBody.append( "<tr>" );
                htmlBody.append( "<td class='key'>" ).append( record.getLabel() ).append( "</td>" );
                htmlBody.append( "<td>" ).append( record.getCount() ).append( "</td>" );
                htmlBody.append( "<td>" ).append( record.getPct() == null ? "" : record.getPct() ).append( "</td>" );
                htmlBody.append( "</tr>" );
            }
            htmlBody.append( "</table>" );
        }

        textBody.append( '\n' );
        htmlBody.append( "<br/>" );

        if ( dailyStatistics != null && !dailyStatistics.isEmpty() )
        {
            // statistics
            htmlBody.append( "<h2>Daily Statistics</h2>" );
            textBody.append( "--Daily Statistics--\n" );
            final Map<String, String> sortedStats = new TreeMap<>( dailyStatistics );

            htmlBody.append( "<table border='1'>" );
            for ( final String key : sortedStats.keySet() )
            {
                final String value = dailyStatistics.get( key );
                textBody.append( key ).append( ": " ).append( value ).append( '\n' );
                htmlBody.append( "<tr><td class='key'>" ).append( key ).append( "</td><td>" ).append( value ).append( "</td></tr>" );
            }
            htmlBody.append( "</table>" );
        }

        htmlBody.append( "</body></html>" );

    }

    private static boolean checkIfEnabled( final PwmDomain pwmDomain, final DailySummaryJobSettings settings )
    {
        if ( pwmDomain == null )
        {
            return false;
        }

        if ( pwmDomain.getConfig() == null )
        {
            return false;
        }

        final List<String> toAddress = settings.getToAddress();
        final String fromAddress = settings.getFromAddress();

        if ( CollectionUtil.isEmpty( toAddress ) || StringUtil.isEmpty( toAddress.get( 0 ) ) )
        {
            return false;
        }

        if ( StringUtil.isEmpty( fromAddress ) )
        {
            return false;
        }

        return settings.isDailySummaryJobsEnabled();
    }

    private static String stripHtmlTags( final String input )
    {
        return input == null ? "" : input.replaceAll( "\\<.*?>", "" );
    }

    private static String wrapText( final String input, final int length )
    {
        final String output = WordUtils.wrap( input, length );
        return output.replace( "\n", "\n   " );
    }

    private static String htmlHeader()
    {
        return "<html><head>"
                + "<style type='text/css'"
                + "\n"
                + "html, body { font-family:Arial, Helvetica, sans-serif; color:#333333; font-size:12px; height:100%; margin:0 }\n"
                + "\n"
                + "a { color:#2D2D2D; text-decoration:underline; font-weight:bold }\n"
                + "p { max-width: 600px; color:#2D2D2D; position:relative; margin-left: auto; margin-right: auto}\n"
                + "hr { float: none; width:100px; position:relative; margin-left:5px; margin-top: 30px; margin-bottom: 30px; }\n"
                + "\n"
                + "h1 { font-size:16px; }\n"
                + "h2 { font-size:14px; }\n"
                + "h3 { font-size:12px; }\n"
                + "\n"
                + "select { font-family:Trebuchet MS, sans-serif; width: 500px }\n"
                + "\n"
                + "table { border-collapse:collapse;  border: 2px solid #D4D4D4; width:100%; margin-left: auto; margin-right: auto }\n"
                + "table td { border: 1px solid #D4D4D4; padding-left: 5px;}\n"
                + "table td.title { text-align:center; font-weight: bold; font-size: 150%; padding-right: 10px; background-color:#DDDDDD }\n"
                + "table td.key { text-align:right; font-weight:bold; padding-right: 10px; width: 200px;}\n"
                + "\n"
                + ".inputfield { width:400px; margin: 5px; height:18px }\n"
                + "\n"
                + "/* main body wrapper, all elements (except footer) should be within wrapper */\n"
                + "#wrapper { width:100%; min-height: 100%; height: auto !important; height: 100%; margin: 0; }\n"
                + "\n"
                + "\n"
                + "/* main content section, all content should be inside a centerbody div */\n"
                + "#centerbody { width:600px; min-width:600px; padding:0; position:relative;"
                + "; margin-left:auto; margin-right:auto; margin-top: 10px; clear:both; padding-bottom:40px;}\n"
                + "\n"
                + "/* all forms use a buttonbar div containing the action buttons */\n"
                + "#buttonbar { margin-top: 30px; width:600px; margin-bottom: 15px; text-align: center}\n"
                + "#buttonbar .btn { font-family:Trebuchet MS, sans-serif; margin-left: 5px; margin-right: 5px; padding: 0 .25em; width: auto; overflow: visible}\n"
                + "\n"
                + "/* used for password complexity meter */\n"
                + "div.progress-container { border: 1px solid #ccc; width: 90px; margin: 2px 5px 2px 0; padding: 1px; float: left; background: white; }\n"
                + "div.progress-container > div { background-color: #ffffff; height: 10px; }\n"
                + "\n"
                + "/* header stuff */\n"
                + "#header         { width:100%; height: 70px; margin: 0; background-image:url('header-gradient.gif') }\n"
                + "#header-page    { width:600px; padding-top:9px; margin-left: auto; margin-right: auto; font-family:Trebuchet MS, sans-serif; font-size:22px; color:#FFFFFF; }\n"
                + "#header-title   { width:600px; margin: auto; font-family:Trebuchet MS, sans-serif; font-size:14px; color:#FFFFFF; }\n"
                + "#header-warning { width:100%; background-color:#FFDC8B; text-align:center; padding-top:4px; padding-bottom:4px }\n"
                + "\n"
                + ".clear { clear:both; }\n"
                + "\n"
                + ".msg-info    { display:block; padding:6px; background-color:#DDDDDD; width: 560px; border-radius:3px; -moz-border-radius:3px}\n"
                + ".msg-error   { display:block; padding:6px; background-color:#FFCD59; width: 560px; border-radius:3px; -moz-border-radius:3px}\n"
                + ".msg-success { display:block; padding:6px; background-color:#EFEFEF; width: 560px; border-radius:3px; -moz-border-radius:3px}\n"
                + "\n"
                + "#footer { position:relative; ;text-align: center; bottom:0; width:100%; color: #BBBBBB; font-size: 11px; height: 30px; margin: 0; margin-top: -30px}\n"
                + "#footer .idle_status { color: #333333; }\n"
                + "\n"
                + "#capslockwarning { font-family: Trebuchet MS, sans-serif; color: #ffffff; font-weight:bold; "
                + "font-variant:small-caps; margin-bottom: 5px; background-color:#d20734; border-radius:3px}\n"
                + "</style></head><body>";
    }
}
