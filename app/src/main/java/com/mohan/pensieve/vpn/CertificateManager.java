package com.mohan.pensieve.vpn;

import android.content.Context;
import android.util.Log;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Generates and manages the local CA certificate used for HTTPS interception.
 * The CA cert needs to be installed by the user once via Settings → Security.
 */
public class CertificateManager {
    private static final String TAG = "CertManager";
    private static final String KEYSTORE_FILE = "pensieve_ca.p12";
    private static final String KEYSTORE_PASS = "pensieve";
    private static final String CA_ALIAS = "pensieve_ca";

    private final Context context;
    private KeyPair caKeyPair;
    private X509Certificate caCert;

    public CertificateManager(Context context) {
        this.context = context;
    }

    public void init() throws Exception {
        File ksFile = new File(context.getFilesDir(), KEYSTORE_FILE);
        if (ksFile.exists()) {
            loadExisting(ksFile);
        } else {
            generateNew(ksFile);
        }
        Log.d(TAG, "CA ready: " + caCert.getSubjectDN());
    }

    private void loadExisting(File ksFile) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(ksFile)) {
            ks.load(fis, KEYSTORE_PASS.toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey(CA_ALIAS, KEYSTORE_PASS.toCharArray());
        caCert = (X509Certificate) ks.getCertificate(CA_ALIAS);
        caKeyPair = new KeyPair(caCert.getPublicKey(), key);
    }

    private void generateNew(File ksFile) throws Exception {
        Log.d(TAG, "Generating new CA keypair...");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        caKeyPair = kpg.generateKeyPair();

        X500Name issuer = new X500Name("CN=Pensieve Local CA, O=Pensieve, C=US");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1));
        Date notAfter  = new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650));

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer, serial, notBefore, notAfter, issuer, caKeyPair.getPublic());

        builder.addExtension(Extension.basicConstraints, true,
                new BasicConstraints(true)); // isCA = true
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                .build(caKeyPair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        caCert = new JcaX509CertificateConverter().getCertificate(holder);

        // Persist to PKCS12 keystore
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(CA_ALIAS, caKeyPair.getPrivate(),
                KEYSTORE_PASS.toCharArray(), new Certificate[]{caCert});
        try (FileOutputStream fos = new FileOutputStream(ksFile)) {
            ks.store(fos, KEYSTORE_PASS.toCharArray());
        }
        Log.d(TAG, "CA generated and saved");
    }

    /**
     * Issue a certificate for a given hostname, signed by our CA.
     * Called for each HTTPS host we intercept.
     */
    public X509Certificate issueCertFor(String hostname) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair hostKeyPair = kpg.generateKeyPair();

        X500Name issuer  = new X500Name("CN=Pensieve Local CA, O=Pensieve, C=US");
        X500Name subject = new X500Name("CN=" + hostname);
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis() + hostname.hashCode());
        Date notBefore = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1));
        Date notAfter  = new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(365));

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer, serial, notBefore, notAfter, subject, hostKeyPair.getPublic());

        // SAN — required by modern browsers/Android
        builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.dNSName, hostname)));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                .build(caKeyPair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    public X509Certificate getCaCert() { return caCert; }
    public KeyPair getCaKeyPair() { return caKeyPair; }

    /** Returns the CA cert as DER bytes for user installation */
    public byte[] getCaCertDer() throws Exception {
        return caCert.getEncoded();
    }
}
