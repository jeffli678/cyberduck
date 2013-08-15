package ch.cyberduck.core.s3;

/*
 * Copyright (c) 2002-2010 David Kocher. All rights reserved.
 *
 * http://cyberduck.ch/
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * Bug fixes, suggestions and comments should be sent to:
 * dkocher@cyberduck.ch
 */

import ch.cyberduck.core.*;
import ch.cyberduck.core.analytics.AnalyticsProvider;
import ch.cyberduck.core.analytics.QloudstatAnalyticsProvider;
import ch.cyberduck.core.cdn.DistributionConfiguration;
import ch.cyberduck.core.cloudfront.WebsiteCloudFrontDistributionConfiguration;
import ch.cyberduck.core.exception.BackgroundException;
import ch.cyberduck.core.exception.LoginFailureException;
import ch.cyberduck.core.features.*;
import ch.cyberduck.core.http.HttpSession;
import ch.cyberduck.core.identity.AWSIdentityConfiguration;
import ch.cyberduck.core.identity.IdentityConfiguration;

import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.AbstractHttpClient;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HttpContext;
import org.apache.log4j.Logger;
import org.jets3t.service.Constants;
import org.jets3t.service.Jets3tProperties;
import org.jets3t.service.ServiceException;
import org.jets3t.service.acl.AccessControlList;
import org.jets3t.service.impl.rest.XmlResponsesSaxParser;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.model.StorageBucket;
import org.jets3t.service.model.StorageBucketLoggingStatus;
import org.jets3t.service.model.StorageObject;
import org.jets3t.service.model.WebsiteConfig;
import org.jets3t.service.security.AWSCredentials;
import org.jets3t.service.security.OAuth2Credentials;
import org.jets3t.service.security.OAuth2Tokens;
import org.jets3t.service.security.ProviderCredentials;
import org.jets3t.service.utils.RestUtils;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * @version $Id$
 */
public class S3Session extends HttpSession<S3Session.RequestEntityRestStorageService> {
    private static final Logger log = Logger.getLogger(S3Session.class);

    private PathContainerService containerService
            = new PathContainerService();

    private S3AccessControlListFeature acl
            = new S3AccessControlListFeature(this);

    private DistributionConfiguration cdn
            = new WebsiteCloudFrontDistributionConfiguration(this);

    public S3Session(Host h) {
        super(h);
    }

    /**
     * Exposing protected methods
     */
    public class RequestEntityRestStorageService extends RestS3Service {
        public RequestEntityRestStorageService(final Jets3tProperties configuration) {
            super(host.getCredentials().isAnonymousLogin() ? null :
                    new AWSCredentials(host.getCredentials().getUsername(), host.getCredentials().getPassword()),
                    new PreferencesUseragentProvider().get(), null, configuration);
        }

        @Override
        protected HttpClient initHttpConnection() {
            final AbstractHttpClient client = connect();
            if(Preferences.instance().getBoolean("s3.expect-continue")) {
                // Activates 'Expect: 100-Continue' handshake for the entity enclosing methods
                HttpProtocolParams.setUseExpectContinue(client.getParams(), true);
            }
            client.setHttpRequestRetryHandler(new RestUtils.JetS3tRetryHandler(5, this));
            return client;
        }

        @Override
        protected boolean isTargettingGoogleStorageService() {
            return getHost().getHostname().equals(Constants.GS_DEFAULT_HOSTNAME);
        }

        @Override
        protected void initializeProxy() {
            // Client already configured
        }

        @Override
        protected void putObjectWithRequestEntityImpl(String bucketName, StorageObject object,
                                                      HttpEntity requestEntity, Map<String, String> requestParams) throws ServiceException {
            super.putObjectWithRequestEntityImpl(bucketName, object, requestEntity, requestParams);
        }

        @Override
        public void verifyExpectedAndActualETagValues(String expectedETag, StorageObject uploadedObject) throws ServiceException {
            if(StringUtils.isBlank(uploadedObject.getETag())) {
                log.warn("No ETag to verify");
                return;
            }
            super.verifyExpectedAndActualETagValues(expectedETag, uploadedObject);
        }

        /**
         * @return the identifier for the signature algorithm.
         */
        @Override
        protected String getSignatureIdentifier() {
            return S3Session.this.getSignatureIdentifier();
        }

        /**
         * @return header prefix for general Google Storage headers: x-goog-.
         */
        @Override
        public String getRestHeaderPrefix() {
            return S3Session.this.getRestHeaderPrefix();
        }

        /**
         * @return header prefix for Google Storage metadata headers: x-goog-meta-.
         */
        @Override
        public String getRestMetadataPrefix() {
            return S3Session.this.getRestMetadataPrefix();
        }

        @Override
        protected XmlResponsesSaxParser getXmlResponseSaxParser() throws ServiceException {
            return S3Session.this.getXmlResponseSaxParser();
        }

        @Override
        public void setBucketLoggingStatusImpl(String bucketName, StorageBucketLoggingStatus status) throws ServiceException {
            super.setBucketLoggingStatusImpl(bucketName, status);
        }

        @Override
        public StorageBucketLoggingStatus getBucketLoggingStatusImpl(String bucketName) throws ServiceException {
            return super.getBucketLoggingStatusImpl(bucketName);
        }

        @Override
        public WebsiteConfig getWebsiteConfigImpl(String bucketName) throws ServiceException {
            return super.getWebsiteConfigImpl(bucketName);
        }

        @Override
        public void setWebsiteConfigImpl(String bucketName, WebsiteConfig config) throws ServiceException {
            super.setWebsiteConfigImpl(bucketName, config);
        }

        @Override
        public void deleteWebsiteConfigImpl(String bucketName) throws ServiceException {
            super.deleteWebsiteConfigImpl(bucketName);
        }

        @Override
        public void authorizeHttpRequest(HttpUriRequest httpMethod, HttpContext context)
                throws ServiceException {
            if(authorize(httpMethod, getProviderCredentials())) {
                return;
            }
            super.authorizeHttpRequest(httpMethod, context);
        }

        @Override
        protected boolean isRecoverable403(HttpUriRequest httpRequest, Exception exception) {
            if(getProviderCredentials() instanceof OAuth2Credentials) {
                OAuth2Tokens tokens;
                try {
                    tokens = ((OAuth2Credentials) getProviderCredentials()).getOAuth2Tokens();
                }
                catch(IOException e) {
                    return false;
                }
                if(tokens != null) {
                    tokens.expireAccessToken();
                    return true;
                }
            }
            return super.isRecoverable403(httpRequest, exception);
        }

        @Override
        protected StorageBucket createBucketImpl(String bucketName, String location,
                                                 AccessControlList acl) throws ServiceException {
            if(StringUtils.isNotBlank(getProjectId())) {
                return super.createBucketImpl(bucketName, location, acl,
                        Collections.<String, Object>singletonMap("x-goog-project-id", getProjectId()));
            }
            return super.createBucketImpl(bucketName, location, acl);
        }

        @Override
        protected StorageBucket[] listAllBucketsImpl() throws ServiceException {
            if(StringUtils.isNotBlank(getProjectId())) {
                return super.listAllBucketsImpl(
                        Collections.<String, Object>singletonMap("x-goog-project-id", getProjectId()));
            }
            return super.listAllBucketsImpl();
        }
    }

    protected boolean authorize(HttpUriRequest httpMethod, ProviderCredentials credentials)
            throws ServiceException {
        return false;
    }

    protected XmlResponsesSaxParser getXmlResponseSaxParser() throws ServiceException {
        return new XmlResponsesSaxParser(client.getJetS3tProperties(), false);
    }

    /**
     * @return the identifier for the signature algorithm.
     */
    protected String getSignatureIdentifier() {
        return "AWS";
    }

    /**
     * @return header prefix for general Google Storage headers: x-goog-.
     */
    protected String getRestHeaderPrefix() {
        return "x-amz-";
    }

    /**
     * @return header prefix for Google Storage metadata headers: x-goog-meta-.
     */
    protected String getRestMetadataPrefix() {
        return "x-amz-meta-";
    }

    protected String getProjectId() {
        return null;
    }

    protected Jets3tProperties configure() {
        final Jets3tProperties configuration = new Jets3tProperties();
        if(log.isDebugEnabled()) {
            log.debug(String.format("Configure for endpoint %s", host));
        }
        configuration.setProperty("s3service.s3-endpoint", host.getProtocol().getDefaultHostname());
        configuration.setProperty("s3service.enable-storage-classes", String.valueOf(true));
        if(StringUtils.isNotBlank(host.getProtocol().getContext())) {
            configuration.setProperty("s3service.s3-endpoint-virtual-path",
                    PathNormalizer.normalize(host.getProtocol().getContext()));
        }
        configuration.setProperty("s3service.https-only", String.valueOf(host.getProtocol().isSecure()));
        if(host.getProtocol().isSecure()) {
            configuration.setProperty("s3service.s3-endpoint-https-port", String.valueOf(host.getPort()));
        }
        else {
            configuration.setProperty("s3service.s3-endpoint-http-port", String.valueOf(host.getPort()));
        }
        // The maximum number of retries that will be attempted when an S3 connection fails
        // with an InternalServer error. To disable retries of InternalError failures, set this to 0.
        configuration.setProperty("s3service.internal-error-retry-max", String.valueOf(0));
        // The maximum number of concurrent communication threads that will be started by
        // the multi-threaded service for upload and download operations.
        configuration.setProperty("s3service.max-thread-count", String.valueOf(1));
        configuration.setProperty("httpclient.proxy-autodetect", String.valueOf(false));
        return configuration;
    }

    @Override
    public RequestEntityRestStorageService connect(final HostKeyController key) throws BackgroundException {
        return new RequestEntityRestStorageService(this.configure());
    }

    @Override
    public void login(final PasswordStore keychain, final LoginController prompt) throws BackgroundException {
        client.setProviderCredentials(host.getCredentials().isAnonymousLogin() ? null :
                new AWSCredentials(host.getCredentials().getUsername(), host.getCredentials().getPassword()));
        try {
            // List all buckets and cache
            final Path root = new Path(String.valueOf(Path.DELIMITER), Path.DIRECTORY_TYPE | Path.VOLUME_TYPE);
            this.cache().put(root.getReference(), this.list(root, new DisabledListProgressListener()));
        }
        catch(BackgroundException e) {
            throw new LoginFailureException(e.getMessage(), e);
        }
    }

    @Override
    public AttributedList<Path> list(final Path file, final ListProgressListener listener) throws BackgroundException {
        if(file.isRoot()) {
            // List all buckets
            return new AttributedList<Path>(new S3BucketListService().list(this));
        }
        else {
            return new S3ObjectListService(this).list(file, listener);
        }
    }

    @Override
    public <T> T getFeature(final Class<T> type) {
        if(type == Read.class) {
            return (T) new S3ReadFeature(this);
        }
        if(type == Write.class) {
            return (T) new S3WriteFeature(this);
        }
        if(type == Upload.class) {
            return (T) new S3ThresholdUploadService(this);
        }
        if(type == Directory.class) {
            return (T) new S3DirectoryFeature(this);
        }
        if(type == Move.class) {
            return (T) new S3MoveFeature(this);
        }
        if(type == Copy.class) {
            return (T) new S3CopyFeature(this);
        }
        if(type == Delete.class) {
            if(this.getHost().getHostname().equals(Constants.S3_DEFAULT_HOSTNAME)) {
                return (T) new S3MultipleDeleteFeature(this);
            }
            return (T) new S3DefaultDeleteFeature(this);
        }
        if(type == AclPermission.class) {
            return (T) acl;
        }
        if(type == Headers.class) {
            return (T) new S3MetadataFeature(this);
        }
        if(type == Touch.class) {
            return (T) new S3TouchFeature(this);
        }
        if(type == Location.class) {
            // Only for AWS
            if(this.getHost().getHostname().equals(Constants.S3_DEFAULT_HOSTNAME)) {
                return (T) new S3LocationFeature(this);
            }
        }
        if(type == AnalyticsProvider.class) {
            // Only for AWS
            if(this.getHost().getHostname().equals(Constants.S3_DEFAULT_HOSTNAME)) {
                return (T) new QloudstatAnalyticsProvider();
            }
            return null;
        }
        if(type == Versioning.class) {
            // Only for AWS
            if(this.getHost().getHostname().equals(Constants.S3_DEFAULT_HOSTNAME)) {
                return (T) new S3VersioningFeature(this);
            }
            return null;
        }
        if(type == Logging.class) {
            // Only for AWS
            if(this.getHost().getHostname().equals(Constants.S3_DEFAULT_HOSTNAME)) {
                return (T) new S3LoggingFeature(this);
            }
            return null;
        }
        if(type == Lifecycle.class) {
            // Only for AWS
            if(this.getHost().getHostname().equals(Constants.S3_DEFAULT_HOSTNAME)) {
                return (T) new S3LifecycleConfiguration(this);
            }
        }
        if(type == Encryption.class) {
            // Only for AWS
            if(this.getHost().getHostname().equals(Constants.S3_DEFAULT_HOSTNAME)) {
                return (T) new S3EncryptionFeature(this);
            }
            return null;
        }
        if(type == Redundancy.class) {
            // Only for AWS
            if(this.getHost().getHostname().equals(Constants.S3_DEFAULT_HOSTNAME)) {
                return (T) new S3StorageClassFeature(this);
            }
            return null;
        }
        if(type == IdentityConfiguration.class) {
            // Only for AWS
            if(this.getHost().getHostname().equals(Constants.S3_DEFAULT_HOSTNAME)) {
                return (T) new AWSIdentityConfiguration(host);
            }
        }
        if(type == DistributionConfiguration.class) {
            if(host.getHostname().endsWith(Constants.S3_DEFAULT_HOSTNAME)) {
                return (T) cdn;
            }
            else {
                // Amazon CloudFront custom origin
                return super.getFeature(type);
            }
        }
        if(type == UrlProvider.class) {
            return (T) new S3UrlProvider(this);
        }
        return super.getFeature(type);
    }
}