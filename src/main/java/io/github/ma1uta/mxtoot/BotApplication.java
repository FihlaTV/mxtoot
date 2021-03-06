/*
 * Copyright sablintolya@gmail.com
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

package io.github.ma1uta.mxtoot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.dropwizard.Application;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.db.PooledDataSourceFactory;
import io.dropwizard.hibernate.HibernateBundle;
import io.dropwizard.hibernate.UnitOfWorkAwareProxyFactory;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.dropwizard.sslreload.SslReloadBundle;
import io.github.ma1uta.matrix.exception.ExceptionHandler;
import io.github.ma1uta.mxtoot.matrix.AppResource;
import io.github.ma1uta.mxtoot.matrix.MxTootBotPool;
import io.github.ma1uta.mxtoot.matrix.MxTootConfig;
import io.github.ma1uta.mxtoot.matrix.MxTootDao;
import io.github.ma1uta.mxtoot.matrix.MxTootPersistentService;
import io.github.ma1uta.mxtoot.matrix.MxTootTransaction;
import io.github.ma1uta.mxtoot.matrix.MxTootTransactionDao;
import io.github.ma1uta.mxtoot.matrix.OldAppResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.ws.rs.client.Client;

/**
 * Matrix bot.
 */
public class BotApplication extends Application<BotConfiguration> {

    private static final Logger LOGGER = LoggerFactory.getLogger(BotApplication.class);

    private HibernateBundle<BotConfiguration> matrixHibernate = new HibernateBundle<BotConfiguration>(MxTootConfig.class,
        MxTootTransaction.class) {
        @Override
        public PooledDataSourceFactory getDataSourceFactory(BotConfiguration configuration) {
            return configuration.getDataSourceFactory();
        }
    };

    /**
     * Entry point.
     *
     * @param args arguments.
     * @throws Exception never throws.
     */
    public static void main(String[] args) throws Exception {
        new BotApplication().run(args);
    }

    @Override
    public void run(BotConfiguration botConfiguration, Environment environment) {
        if (botConfiguration.isDisableCertValidation()) {
            disableCertValidation();
        }
        matrixBot(botConfiguration, environment);
    }

    @Override
    public void initialize(Bootstrap<BotConfiguration> bootstrap) {
        bootstrap.setConfigurationSourceProvider(
            new SubstitutingSourceProvider(bootstrap.getConfigurationSourceProvider(), new EnvironmentVariableSubstitutor(false)));
        bootstrap.addBundle(new SslReloadBundle());

        bootstrap.getObjectMapper().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

        bootstrap.addBundle(matrixHibernate);
    }

    @SuppressWarnings("unchecked")
    private void matrixBot(BotConfiguration botConfiguration, Environment environment) {
        environment.getObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, botConfiguration.isStrictMode());
        environment.getObjectMapper().configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

        Client jersey = new JerseyClientBuilder(environment).using(botConfiguration.getJerseyClient()).build("jersey");

        UnitOfWorkAwareProxyFactory proxyFactory = new UnitOfWorkAwareProxyFactory(matrixHibernate);
        MxTootDao mxTootDao = new MxTootDao(matrixHibernate.getSessionFactory());
        MxTootTransactionDao mxTootTransactionDao = new MxTootTransactionDao(matrixHibernate.getSessionFactory());

        MxTootPersistentService<MxTootDao> botService = proxyFactory.create(MxTootPersistentService.class, Object.class, mxTootDao);
        MxTootPersistentService<MxTootTransactionDao> transactionService = proxyFactory.create(MxTootPersistentService.class, Object.class,
            mxTootTransactionDao);
        MxTootBotPool mxTootBotPool = new MxTootBotPool(botConfiguration, botService, jersey, botConfiguration.getCommands());

        environment.lifecycle().manage(mxTootBotPool);
        AppResource appResource = new AppResource(mxTootTransactionDao, mxTootBotPool, botConfiguration.getHsToken(),
            botConfiguration.getHomeserverUrl(),
            botService, transactionService);
        environment.jersey().register(appResource);
        environment.jersey().register(new OldAppResource(appResource));
        environment.jersey().register(new ExceptionHandler());
    }

    protected void disableCertValidation() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[] {new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return null;
                }

                public void checkClientTrusted(X509Certificate[] certs, String authType) {
                }

                public void checkServerTrusted(X509Certificate[] certs, String authType) {
                }
            } };

            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        } catch (Exception e) {
            LOGGER.error("Failed disable cert validation", e);
            throw new Error(e);
        }
    }
}
