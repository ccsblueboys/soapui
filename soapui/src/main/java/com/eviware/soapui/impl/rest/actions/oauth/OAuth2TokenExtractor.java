package com.eviware.soapui.impl.rest.actions.oauth;

import com.eviware.soapui.SoapUI;
import com.eviware.soapui.impl.wsdl.support.http.HttpClientSupport;
import com.eviware.soapui.support.StringUtils;
import com.eviware.soapui.support.TimeUtils;
import org.apache.oltu.oauth2.client.OAuthClient;
import org.apache.oltu.oauth2.client.request.OAuthClientRequest;
import org.apache.oltu.oauth2.client.response.OAuthJSONAccessTokenResponse;
import org.apache.oltu.oauth2.common.exception.OAuthProblemException;
import org.apache.oltu.oauth2.common.exception.OAuthSystemException;
import org.apache.oltu.oauth2.common.message.types.GrantType;
import org.apache.oltu.oauth2.common.token.OAuthToken;
import org.apache.oltu.oauth2.common.utils.OAuthUtils;
import org.apache.oltu.oauth2.httpclient4.HttpClient4;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 *
 */
public class OAuth2TokenExtractor
{

	public static final String CODE = "code";
	public static final String TITLE = "<TITLE>";
	public static final String TOKEN = "token";
	public static final String ACCESS_TOKEN = "access_token";

	protected List<BrowserListener> browserListeners = new ArrayList<BrowserListener>();

	public void extractAccessToken( final OAuth2Parameters parameters ) throws OAuthSystemException, MalformedURLException, URISyntaxException
	{
		switch( parameters.getOAuth2Flow() )
		{
			case IMPLICIT_GRANT:
				extractAccessTokenForImplicitGrantFlow( parameters );
				break;
			case AUTHORIZATION_CODE_GRANT:
			default:
				extractAccessTokenForAuthorizationCodeGrantFlow( parameters );
				break;
		}
	}

	void extractAccessTokenForAuthorizationCodeGrantFlow( final OAuth2Parameters parameters ) throws URISyntaxException,
			MalformedURLException, OAuthSystemException
	{
		final UserBrowserFacade browserFacade = getBrowserFacade();
		addBrowserInteractionHandler( browserFacade, parameters );
		addExternalListeners( browserFacade );
		browserFacade.addBrowserListener( new BrowserListenerAdapter()
		{
			@Override
			public void locationChanged( String newLocation )
			{
				getAccessTokenAndSaveToProfile( browserFacade, parameters, extractAuthorizationCodeFromForm( extractFormData( newLocation ), CODE ) );
			}

			@Override
			public void contentChanged( String newContent )
			{
				int titlePosition = newContent.indexOf( TITLE );
				if( titlePosition != -1 )
				{
					String title = newContent.substring( titlePosition + TITLE.length(), newContent.indexOf( "</TITLE>" ) );
					getAccessTokenAndSaveToProfile( browserFacade, parameters, extractAuthorizationCodeFromTitle( title ) );
				}
			}

			@Override
			public void browserClosed()
			{
				super.browserClosed();
				if( !parameters.isAccessTokenRetrivedFromServer() )
				{
					setRetrievedCanceledStatus( parameters );
				}
			}
		} );
		browserFacade.open( new URI( createAuthorizationURL( parameters, CODE ) ).toURL() );
		parameters.waitingForAuthorization();
	}

	void extractAccessTokenForImplicitGrantFlow( final OAuth2Parameters parameters ) throws OAuthSystemException,
			URISyntaxException, MalformedURLException
	{
		final UserBrowserFacade browserFacade = getBrowserFacade();
		addBrowserInteractionHandler( browserFacade, parameters );
		addExternalListeners( browserFacade );
		browserFacade.addBrowserListener( new BrowserListenerAdapter()
		{
			@Override
			public void locationChanged( String newLocation )
			{
				String accessToken = extractAuthorizationCodeFromForm( extractFormData( newLocation ), ACCESS_TOKEN );
				if( !StringUtils.isNullOrEmpty( accessToken ) )
				{
					parameters.setAccessTokenInProfile( accessToken );
					parameters.setRefreshTokenInProfile( null );
					parameters.setAccessTokenExpirationTimeInProfile( 0 );
					parameters.setAccessTokenIssuedTimeInProfile( TimeUtils.getCurrentTimeInSeconds() );
					browserFacade.close();
				}
			}

			@Override
			public void browserClosed()
			{
				super.browserClosed();
				setRetrievedCanceledStatus( parameters );
			}
		} );
		browserFacade.open( new URI( createAuthorizationURL( parameters, TOKEN ) ).toURL() );
		parameters.waitingForAuthorization();
	}

	void refreshAccessToken( OAuth2Parameters parameters ) throws OAuthProblemException, OAuthSystemException
	{
		OAuthClientRequest accessTokenRequest = OAuthClientRequest
				.tokenLocation( parameters.accessTokenUri )
				.setGrantType( GrantType.REFRESH_TOKEN )
				.setClientId( parameters.clientId )
				.setClientSecret( parameters.clientSecret )
				.setRefreshToken( parameters.refreshToken )
				.buildBodyMessage();

		OAuthClient oAuthClient = getOAuthClient();

		OAuthToken oAuthToken = oAuthClient.accessToken( accessTokenRequest, OAuthJSONAccessTokenResponse.class ).getOAuthToken();
		parameters.applyRetrievedAccessToken( oAuthToken.getAccessToken() );
		parameters.setAccessTokenIssuedTimeInProfile( TimeUtils.getCurrentTimeInSeconds() );
	}

	public void addBrowserListener( BrowserListener listener )
	{
		browserListeners.add( listener );
	}

	protected OAuthClient getOAuthClient()
	{
		return new OAuthClient( new HttpClient4( HttpClientSupport.getHttpClient() ) );
	}

	protected UserBrowserFacade getBrowserFacade()
	{
		return new WebViewUserBrowserFacade();
	}

	/* Helper methods */

	private void setRetrievedCanceledStatus( OAuth2Parameters parameters )
	{
		parameters.retrivalCanceled();
	}

	private void addExternalListeners( UserBrowserFacade browserFacade )
	{
		for( BrowserListener browserListener : browserListeners )
		{
			browserFacade.addBrowserListener( browserListener );
		}
	}

	private void addBrowserInteractionHandler( UserBrowserFacade browserFacade, OAuth2Parameters parameters )
	{
		if( parameters.getJavaScripts().isEmpty() )
		{
			return;
		}
		browserFacade.addBrowserListener( new BrowserInteractionMonitor( browserFacade, parameters.getJavaScripts() ) );
	}

	private String createAuthorizationURL( OAuth2Parameters parameters, String responseType )
			throws OAuthSystemException
	{
		return OAuthClientRequest
				.authorizationLocation( parameters.authorizationUri )
				.setClientId( parameters.clientId )
				.setResponseType( responseType )
				.setScope( parameters.scope )
				.setRedirectURI( parameters.redirectUri )
				.buildQueryMessage().getLocationUri();
	}

	private String extractFormData( String url )
	{
		int questionMarkIndex = url.indexOf( '?' );
		if( questionMarkIndex != -1 )
		{
			return url.substring( questionMarkIndex + 1 );
		}

		int hashIndex = url.indexOf( "#" );
		if( hashIndex != -1 )
		{
			return url.substring( hashIndex + 1 );
		}
		return "";
	}

	private String extractAuthorizationCodeFromTitle( String title )
	{
		if( title.contains( "code=" ) )
		{
			return title.substring( title.indexOf( "code=" ) + 5 );
		}
		return null;
	}

	private String extractAuthorizationCodeFromForm( String formData, String parameterName )
	{
		return ( String )OAuthUtils.decodeForm( formData ).get( parameterName );
	}

	private void getAccessTokenAndSaveToProfile( UserBrowserFacade browserFacade, OAuth2Parameters parameters, String authorizationCode )
	{
		if( authorizationCode != null )
		{
			try
			{
				parameters.receivedAuthorizationCode();
				OAuthClientRequest accessTokenRequest = OAuthClientRequest
						.tokenLocation( parameters.accessTokenUri )
						.setGrantType( GrantType.AUTHORIZATION_CODE )
						.setClientId( parameters.clientId )
						.setClientSecret( parameters.clientSecret )
						.setRedirectURI( parameters.redirectUri )
						.setCode( authorizationCode )
						.buildBodyMessage();
				OAuthToken token = getOAuthClient().accessToken( accessTokenRequest, OAuth2AccessTokenResponse.class )
						.getOAuthToken();
				if( token != null && token.getAccessToken() != null )
				{
					parameters.setAccessTokenInProfile( token.getAccessToken() );
					parameters.setRefreshTokenInProfile( token.getRefreshToken() );
					if( token.getExpiresIn() != null )
					{
						parameters.setAccessTokenExpirationTimeInProfile( token.getExpiresIn() );
					}
					parameters.setAccessTokenIssuedTimeInProfile( TimeUtils.getCurrentTimeInSeconds() );

					browserFacade.close();
				}
			}
			catch( OAuthSystemException e )
			{
				SoapUI.logError( e );
			}
			catch( OAuthProblemException e )
			{
				SoapUI.logError( e );
			}
		}
	}

	/* Helper class that runs automation JavaScripts registered in the OAuth2 profile */

	private class BrowserInteractionMonitor extends BrowserListenerAdapter
	{
		private final List<String> javaScripts;
		int pageIndex = 0;
		private UserBrowserFacade browserFacade;

		public BrowserInteractionMonitor( UserBrowserFacade browserFacade, List<String> javaScripts )
		{
			this.browserFacade = browserFacade;
			this.javaScripts = javaScripts;
		}

		@Override
		public void contentChanged( String newContent )
		{
			String script = javaScripts.get( pageIndex );
			if( javaScripts.size() > pageIndex )
			{
				try
				{
					browserFacade.executeJavaScript( script );
				}
				catch( Exception e )
				{
					SoapUI.log.warn( "Error when running JavaScript [" + script + "]: " + e.getMessage() );
				}
				pageIndex++;
			}
		}

	}
}
