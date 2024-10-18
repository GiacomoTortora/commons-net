/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

 package org.apache.commons.net.ftp;

 import java.security.KeyStore;
 import java.security.cert.CertificateException;
 import java.security.cert.X509Certificate;
 
 import javax.net.ssl.TrustManager;
 import javax.net.ssl.TrustManagerFactory;
 import javax.net.ssl.X509TrustManager;

 /**
 * Exception thrown by FTPSTrustManager.
 */
class FTPSTrustManagerException extends Exception {
    public FTPSTrustManagerException(String message, Throwable cause) {
        super(message, cause);
    }
}
 
 /**
  * Enables server certificate validation on this SSL/TLS connection.
  *
  * @since 2.0
  * @deprecated 3.0 use {@link org.apache.commons.net.util.TrustManagerUtils#getValidateServerCertificateTrustManager()
  *             TrustManagerUtils#getValidateServerCertificateTrustManager()} instead
  */
 @Deprecated
 public class FTPSTrustManager implements X509TrustManager {
 
     private final X509TrustManager defaultTrustManager;
 
     // Constructor to initialize default TrustManager
     public FTPSTrustManager() throws FTPSTrustManagerException {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null); // Load default TrustStore
            tmf.init(ks);
            TrustManager[] trustManagers = tmf.getTrustManagers();
            defaultTrustManager = (X509TrustManager) trustManagers[0]; // Default TrustManager
        } catch (Exception e) {
            throw new FTPSTrustManagerException("Failed to initialize FTPSTrustManager", e);
        }
    }
 
     /**
      * Validates the client's certificate.
      */
     @Override
     public void checkClientTrusted(final X509Certificate[] certificates, final String authType) throws CertificateException {
         // Delegate to the default TrustManager to validate the client certificates
         defaultTrustManager.checkClientTrusted(certificates, authType);
     }
 
     /**
      * Validates the server's certificate.
      */
     @Override
     public void checkServerTrusted(final X509Certificate[] certificates, final String authType) throws CertificateException {
         // Delegate to the default TrustManager to validate the server certificates
         defaultTrustManager.checkServerTrusted(certificates, authType);
     }
 
     /**
      * Returns the accepted issuers.
      */
     @Override
     public X509Certificate[] getAcceptedIssuers() {
         return defaultTrustManager.getAcceptedIssuers();
     }
 }