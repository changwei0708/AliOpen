/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.alibaba.tuna.netty.handler.ssl;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSessionContext;

import com.alibaba.tuna.netty.buffer.ByteBuf;
import com.alibaba.tuna.netty.buffer.ByteBufInputStream;

import java.io.File;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A server-side {@link SslContext} which uses JDK's SSL/TLS implementation.
 */
public final class JdkSslServerContext extends JdkSslContext {

    private final SSLContext ctx;
    private final List<String> nextProtocols;

    /**
     * Creates a new instance.
     *
     * @param certChainFile an X.509 certificate chain file in PEM format
     * @param keyFile a PKCS#8 private key file in PEM format
     */
    public JdkSslServerContext(File certChainFile, File keyFile) throws SSLException {
        this(certChainFile, keyFile, null);
    }

    /**
     * Creates a new instance.
     *
     * @param certChainFile an X.509 certificate chain file in PEM format
     * @param keyFile a PKCS#8 private key file in PEM format
     * @param keyPassword the password of the {@code keyFile}.
     *                    {@code null} if it's not password-protected.
     */
    public JdkSslServerContext(File certChainFile, File keyFile, String keyPassword) throws SSLException {
        this(certChainFile, keyFile, keyPassword, null, null, 0, 0);
    }

    /**
     * Creates a new instance.
     *
     * @param certChainFile an X.509 certificate chain file in PEM format
     * @param keyFile a PKCS#8 private key file in PEM format
     * @param keyPassword the password of the {@code keyFile}.
     *                    {@code null} if it's not password-protected.
     * @param ciphers the cipher suites to enable, in the order of preference.
     *                {@code null} to use the default cipher suites.
     * @param nextProtocols the application layer protocols to accept, in the order of preference.
     *                      {@code null} to disable TLS NPN/ALPN extension.
     * @param sessionCacheSize the size of the cache used for storing SSL session objects.
     *                         {@code 0} to use the default value.
     * @param sessionTimeout the timeout for the cached SSL session objects, in seconds.
     *                       {@code 0} to use the default value.
     */
    public JdkSslServerContext(
            File certChainFile, File keyFile, String keyPassword,
            Iterable<String> ciphers, Iterable<String> nextProtocols,
            long sessionCacheSize, long sessionTimeout) throws SSLException {

        super(ciphers);

        if (certChainFile == null) {
            throw new NullPointerException("certChainFile");
        }
        if (keyFile == null) {
            throw new NullPointerException("keyFile");
        }

        if (keyPassword == null) {
            keyPassword = "";
        }

        if (nextProtocols != null && nextProtocols.iterator().hasNext()) {

            List<String> list = new ArrayList<String>();
            for (String p: nextProtocols) {
                if (p == null) {
                    break;
                }
                list.add(p);
            }

            this.nextProtocols = Collections.unmodifiableList(list);
        } else {
            this.nextProtocols = Collections.emptyList();
        }

        String algorithm = Security.getProperty("ssl.KeyManagerFactory.algorithm");
        if (algorithm == null) {
            algorithm = "SunX509";
        }

        try {
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(null, null);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            KeyFactory rsaKF = KeyFactory.getInstance("RSA");
            KeyFactory dsaKF = KeyFactory.getInstance("DSA");

            ByteBuf encodedKeyBuf = PemReader.readPrivateKey(keyFile);
            byte[] encodedKey = new byte[encodedKeyBuf.readableBytes()];
            encodedKeyBuf.readBytes(encodedKey).release();
            PKCS8EncodedKeySpec encodedKeySpec = new PKCS8EncodedKeySpec(encodedKey);

            PrivateKey key;
            try {
                key = rsaKF.generatePrivate(encodedKeySpec);
            } catch (InvalidKeySpecException ignore) {
                key = dsaKF.generatePrivate(encodedKeySpec);
            }

            List<Certificate> certChain = new ArrayList<Certificate>();
            ByteBuf[] certs = PemReader.readCertificates(certChainFile);
            try {
                for (ByteBuf buf: certs) {
                    certChain.add(cf.generateCertificate(new ByteBufInputStream(buf)));
                }
            } finally {
                for (ByteBuf buf: certs) {
                    buf.release();
                }
            }

            ks.setKeyEntry("key", key, keyPassword.toCharArray(), certChain.toArray(new Certificate[certChain.size()]));

            // Set up key manager factory to use our key store
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(algorithm);
            kmf.init(ks, keyPassword.toCharArray());

            // Initialize the SSLContext to work with our key managers.
            ctx = SSLContext.getInstance(PROTOCOL);
            ctx.init(kmf.getKeyManagers(), null, null);

            SSLSessionContext sessCtx = ctx.getServerSessionContext();
            if (sessionCacheSize > 0) {
                sessCtx.setSessionCacheSize((int) Math.min(sessionCacheSize, Integer.MAX_VALUE));
            }
            if (sessionTimeout > 0) {
                sessCtx.setSessionTimeout((int) Math.min(sessionTimeout, Integer.MAX_VALUE));
            }
        } catch (Exception e) {
            throw new SSLException("failed to initialize the server-side SSL context", e);
        }
    }

    @Override
    public boolean isClient() {
        return false;
    }

    @Override
    public List<String> nextProtocols() {
        return nextProtocols;
    }

    @Override
    public SSLContext context() {
        return ctx;
    }
}
