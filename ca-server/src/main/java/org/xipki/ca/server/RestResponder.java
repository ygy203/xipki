/*
 *
 * Copyright (c) 2013 - 2019 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.ca.server;

import static org.xipki.audit.AuditLevel.ERROR;
import static org.xipki.audit.AuditLevel.INFO;
import static org.xipki.audit.AuditStatus.FAILED;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.math.BigInteger;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.bouncycastle.asn1.pkcs.CertificationRequest;
import org.bouncycastle.asn1.pkcs.CertificationRequestInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.audit.AuditEvent;
import org.xipki.audit.AuditLevel;
import org.xipki.audit.AuditStatus;
import org.xipki.ca.api.CertificateInfo;
import org.xipki.ca.api.InsuffientPermissionException;
import org.xipki.ca.api.NameId;
import org.xipki.ca.api.OperationException;
import org.xipki.ca.api.OperationException.ErrorCode;
import org.xipki.ca.api.RequestType;
import org.xipki.ca.api.RestAPIConstants;
import org.xipki.ca.api.mgmt.CaStatus;
import org.xipki.ca.api.mgmt.PermissionConstants;
import org.xipki.ca.api.mgmt.RequestorInfo;
import org.xipki.ca.server.cmp.CmpResponder;
import org.xipki.security.CrlReason;
import org.xipki.security.X509Cert;
import org.xipki.security.util.X509Util;
import org.xipki.util.Args;
import org.xipki.util.Base64;
import org.xipki.util.DateUtil;
import org.xipki.util.LogUtil;
import org.xipki.util.PemEncoder;
import org.xipki.util.PemEncoder.PemLabel;
import org.xipki.util.RandomUtil;
import org.xipki.util.StringUtil;

/**
 * REST API responder.
 *
 * @author Lijun Liao
 * @since 3.0.1
 */

public class RestResponder {

  public static class RestResponse {

    private int statusCode;

    private String contentType;

    private Map<String, String> headers = new HashMap<>();

    private byte[] body;

    public RestResponse(int statusCode, String contentType, Map<String, String> headers,
        byte[] body) {
      this.statusCode = statusCode;
      this.contentType = contentType;
      this.headers = headers;
      this.body = body;
    }

    public int getStatusCode() {
      return statusCode;
    }

    public void setStatusCode(int statusCode) {
      this.statusCode = statusCode;
    }

    public String getContentType() {
      return contentType;
    }

    public void setContentType(String contentType) {
      this.contentType = contentType;
    }

    public Map<String, String> getHeaders() {
      return headers;
    }

    public void setHeaders(Map<String, String> headers) {
      this.headers = headers;
    }

    public byte[] getBody() {
      return body;

    }

    public void setBody(byte[] body) {
      this.body = body;
    }

  } // class RestResponse

  private static class HttpRespAuditException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int httpStatus;

    private final String auditMessage;

    private final AuditLevel auditLevel;

    private AuditStatus auditStatus;

    public HttpRespAuditException(int httpStatus, String auditMessage,
        AuditLevel auditLevel, AuditStatus auditStatus) {
      this.httpStatus = httpStatus;
      this.auditMessage = Args.notBlank(auditMessage, "auditMessage");
      this.auditLevel = Args.notNull(auditLevel, "auditLevel");
      this.auditStatus = Args.notNull(auditStatus, "auditStatus");
    }

    public int getHttpStatus() {
      return httpStatus;
    }

    public String getAuditMessage() {
      return auditMessage;
    }

    public AuditLevel getAuditLevel() {
      return auditLevel;
    }

    public AuditStatus getAuditStatus() {
      return auditStatus;
    }

  } // class HttpRespAuditException

  private static final int OK = 200;

  private static final int BAD_REQUEST = 400;

  private static final int UNAUTHORIZED = 401;

  private static final int NOT_FOUND = 404;

  private static final int CONFLICT = 409;

  private static final int UNSUPPORTED_MEDIA_TYPE = 415;

  private static final int INTERNAL_SERVER_ERROR = 500;

  private static final int SERVICE_UNAVAILABLE = 503;

  private static final Logger LOG = LoggerFactory.getLogger(RestResponder.class);

  private final CaManagerImpl responderManager;

  public RestResponder(CaManagerImpl responderManager) {
    this.responderManager = responderManager;
  }

  public RestResponse service(String path, AuditEvent event, byte[] request,
      HttpRequestMetadataRetriever httpRetriever) {
    event.setApplicationName(CaAuditConstants.APPNAME);
    event.setName(CaAuditConstants.NAME_perf);
    event.addEventData(CaAuditConstants.NAME_req_type, RequestType.REST.name());

    String msgId = RandomUtil.nextHexLong();
    event.addEventData(CaAuditConstants.NAME_mid, msgId);

    AuditLevel auditLevel = AuditLevel.INFO;
    AuditStatus auditStatus = AuditStatus.SUCCESSFUL;
    String auditMessage = null;

    try {
      if (responderManager == null) {
        String message = "responderManager in servlet not configured";
        LOG.error(message);
        throw new HttpRespAuditException(INTERNAL_SERVER_ERROR, message, ERROR, FAILED);
      }

      String caName = null;
      String command = null;

      X509Ca ca = null;
      if (path.length() > 1) {
        // the first char is always '/'
        String coreUri = path;
        int sepIndex = coreUri.indexOf('/', 1);
        if (sepIndex == -1 || sepIndex == coreUri.length() - 1) {
          String message = "invalid path " + path;
          LOG.error(message);
          throw new HttpRespAuditException(NOT_FOUND, message, ERROR, FAILED);
        }

        // skip also the first char ('/')
        String caAlias = coreUri.substring(1, sepIndex).toLowerCase();
        command = coreUri.substring(sepIndex + 1);

        caName = responderManager.getCaNameForAlias(caAlias);
        if (caName == null) {
          caName = caAlias;
        }

        CmpResponder caResponder = responderManager.getX509CaResponder(caName);
        if (caResponder != null) {
          ca = caResponder.getCa();
        }
      }

      if (caName == null || ca == null || !ca.getCaInfo().supportsRest()
          || ca.getCaInfo().getStatus() != CaStatus.ACTIVE) {
        String message;
        if (caName == null) {
          message = "no CA is specified";
        } else if (ca == null) {
          message = "unknown CA '" + caName + "'";
        } else if (!ca.getCaInfo().supportsRest()) {
          message = "REST is not supported by the CA '" + caName + "'";
        } else {
          message = "CA '" + caName + "' is out of service";
        }
        LOG.warn(message);
        throw new HttpRespAuditException(NOT_FOUND, message, INFO, FAILED);
      }

      event.addEventData(CaAuditConstants.NAME_ca, ca.getCaIdent().getName());
      event.addEventType(command);

      RequestorInfo requestor;
      // Retrieve the user:password
      String hdrValue = httpRetriever.getHeader("Authorization");
      if (hdrValue != null && hdrValue.startsWith("Basic ")) {
        String user = null;
        byte[] password = null;
        if (hdrValue.length() > 6) {
          String b64 = hdrValue.substring(6);
          byte[] userPwd = Base64.decodeFast(b64);
          int idx = -1;
          for (int i = 0; i < userPwd.length; i++) {
            if (userPwd[i] == ':') {
              idx = i;
              break;
            }
          }

          if (idx != -1 && idx < userPwd.length - 1) {
            user = new String(Arrays.copyOfRange(userPwd, 0, idx));
            password = Arrays.copyOfRange(userPwd, idx + 1, userPwd.length);
          }
        }

        if (user == null) {
          throw new HttpRespAuditException(UNAUTHORIZED, "invalid Authorization information",
              INFO, FAILED);
        }
        NameId userIdent = ca.authenticateUser(user, password);
        if (userIdent == null) {
          throw new HttpRespAuditException(UNAUTHORIZED, "could not authenticate user",
              INFO, FAILED);
        }
        requestor = ca.getByUserRequestor(userIdent);
      } else {
        X509Certificate clientCert = httpRetriever.getTlsClientCert();
        if (clientCert == null) {
          throw new HttpRespAuditException(UNAUTHORIZED, "no client certificate", INFO, FAILED);
        }
        requestor = ca.getRequestor(clientCert);
      }

      if (requestor == null) {
        throw new OperationException(ErrorCode.NOT_PERMITTED, "no requestor specified");
      }

      event.addEventData(CaAuditConstants.NAME_requestor, requestor.getIdent().getName());

      String respCt = null;
      byte[] respBytes = null;

      if (RestAPIConstants.CMD_cacert.equalsIgnoreCase(command)) {
        respCt = RestAPIConstants.CT_pkix_cert;
        respBytes = ca.getCaInfo().getCert().getEncodedCert();
      } else if (RestAPIConstants.CMD_dhpoc_certs.equalsIgnoreCase(command)) {
        DhpocControl control = responderManager.getX509Ca(caName).getCaInfo().getDhpocControl();
        if (control == null) {
          respBytes = new byte[0];
        } else {
          respCt = RestAPIConstants.CT_pem_file;
          respBytes = StringUtil.toUtf8Bytes(
                        X509Util.encodeCertificates(control.getCertificates()));
        }
      } else if (RestAPIConstants.CMD_cacertchain.equalsIgnoreCase(command)) {
        respCt = RestAPIConstants.CT_pem_file;
        List<X509Cert> certchain = ca.getCaInfo().getCertchain();
        int size = 1 + (certchain == null ? 0 : certchain.size());
        X509Cert[] certchainWithCaCert = new X509Cert[size];
        certchainWithCaCert[0] = ca.getCaInfo().getCert();
        if (size > 1) {
          for (int i = 1; i < size; i++) {
            certchainWithCaCert[i] = certchain.get(i - 1);
          }
        }

        respBytes = StringUtil.toUtf8Bytes(X509Util.encodeCertificates(certchainWithCaCert));
      } else if (RestAPIConstants.CMD_enroll_cert.equalsIgnoreCase(command)
          || RestAPIConstants.CMD_enroll_cert_cagenkeypair.equalsIgnoreCase(command)) {
        String profile = httpRetriever.getParameter(RestAPIConstants.PARAM_profile);
        if (StringUtil.isBlank(profile)) {
          throw new HttpRespAuditException(BAD_REQUEST,
              "required parameter " + RestAPIConstants.PARAM_profile + " not specified",
              INFO, FAILED);
        }
        profile = profile.toLowerCase();

        try {
          requestor.assertPermitted(PermissionConstants.ENROLL_CERT);
        } catch (InsuffientPermissionException ex) {
          throw new OperationException(ErrorCode.NOT_PERMITTED, ex.getMessage());
        }

        if (!requestor.isCertprofilePermitted(profile)) {
          throw new OperationException(ErrorCode.NOT_PERMITTED,
              "certprofile " + profile + " is not allowed");
        }

        String strNotBefore = httpRetriever.getParameter(RestAPIConstants.PARAM_not_before);
        Date notBefore = (strNotBefore == null) ? null
            : DateUtil.parseUtcTimeyyyyMMddhhmmss(strNotBefore);

        String strNotAfter = httpRetriever.getParameter(RestAPIConstants.PARAM_not_after);
        Date notAfter = (strNotAfter == null) ? null
            : DateUtil.parseUtcTimeyyyyMMddhhmmss(strNotAfter);

        if (RestAPIConstants.CMD_enroll_cert_cagenkeypair.equalsIgnoreCase(command)) {
          String ct = httpRetriever.getHeader("Content-Type");

          X500Name subject;
          Extensions extensions;

          if (ct.startsWith("text/plain")) {
            Properties props = new Properties();
            props.load(new ByteArrayInputStream(request));
            String strSubject = props.getProperty("subject");
            if (strSubject == null) {
              throw new OperationException(ErrorCode.BAD_CERT_TEMPLATE, "subject is not specified");
            }

            try {
              subject = new X500Name(strSubject);
            } catch (Exception ex) {
              throw new OperationException(ErrorCode.BAD_CERT_TEMPLATE, "invalid subject");
            }
            extensions = null;
          } else if (RestAPIConstants.CT_pkcs10.equalsIgnoreCase(ct)) {
            // The PKCS#10 will only be used for transport of subject and extensions.
            // The associated key will not be used, so the verification of POPO is skipped.
            CertificationRequestInfo certTemp =
                CertificationRequest.getInstance(request).getCertificationRequestInfo();
            subject = certTemp.getSubject();
            extensions = CaUtil.getExtensions(certTemp);
          } else {
            String message = "unsupported media type " + ct;
            throw new HttpRespAuditException(UNSUPPORTED_MEDIA_TYPE, message, INFO, FAILED);
          }

          CertTemplateData certTemplate = new CertTemplateData(subject, null,
              notBefore, notAfter, extensions, profile, null, true);
          CertificateInfo certInfo = ca.generateCert(certTemplate, requestor, RequestType.REST,
              null, msgId);

          if (ca.getCaInfo().isSaveRequest()) {
            long dbId = ca.addRequest(request);
            ca.addRequestCert(dbId, certInfo.getCert().getCertId());
          }

          respCt = RestAPIConstants.CT_pem_file;
          byte[] keyBytes =
              PemEncoder.encode(certInfo.getPrivateKey().getEncoded(), PemLabel.PRIVATE_KEY);
          byte[] certBytes =
              PemEncoder.encode(certInfo.getCert().getEncodedCert(), PemLabel.CERTIFICATE);

          respBytes = new byte[keyBytes.length + 2 + certBytes.length];
          System.arraycopy(keyBytes, 0, respBytes, 0, keyBytes.length);
          respBytes[keyBytes.length] = '\r';
          respBytes[keyBytes.length + 1] = '\n';
          System.arraycopy(certBytes, 0, respBytes, keyBytes.length + 2, certBytes.length);
        } else {
          String ct = httpRetriever.getHeader("Content-Type");
          if (!RestAPIConstants.CT_pkcs10.equalsIgnoreCase(ct)) {
            String message = "unsupported media type " + ct;
            throw new HttpRespAuditException(UNSUPPORTED_MEDIA_TYPE, message, INFO, FAILED);
          }

          CertificationRequest csr = CertificationRequest.getInstance(request);
          if (!ca.verifyCsr(csr)) {
            throw new OperationException(ErrorCode.BAD_POP);
          }

          CertificationRequestInfo certTemp = csr.getCertificationRequestInfo();

          X500Name subject = certTemp.getSubject();
          SubjectPublicKeyInfo publicKeyInfo = certTemp.getSubjectPublicKeyInfo();

          Extensions extensions = CaUtil.getExtensions(certTemp);
          CertTemplateData certTemplate = new CertTemplateData(subject, publicKeyInfo,
              notBefore, notAfter, extensions, profile);
          CertificateInfo certInfo = ca.generateCert(certTemplate, requestor, RequestType.REST,
              null, msgId);

          if (ca.getCaInfo().isSaveRequest()) {
            long dbId = ca.addRequest(request);
            ca.addRequestCert(dbId, certInfo.getCert().getCertId());
          }

          X509Cert cert = certInfo.getCert();
          if (cert == null) {
            String message = "could not generate certificate";
            LOG.warn(message);
            throw new HttpRespAuditException(INTERNAL_SERVER_ERROR, message, INFO, FAILED);
          }
          respCt = RestAPIConstants.CT_pkix_cert;
          respBytes = cert.getEncodedCert();
        }
      } else if (RestAPIConstants.CMD_revoke_cert.equalsIgnoreCase(command)
          || RestAPIConstants.CMD_delete_cert.equalsIgnoreCase(command)) {
        int permission;
        if (RestAPIConstants.CMD_revoke_cert.equalsIgnoreCase(command)) {
          permission = PermissionConstants.REVOKE_CERT;
        } else {
          permission = PermissionConstants.REMOVE_CERT;
        }
        try {
          requestor.assertPermitted(permission);
        } catch (InsuffientPermissionException ex) {
          throw new OperationException(ErrorCode.NOT_PERMITTED, ex.getMessage());
        }

        String strCaSha1 = httpRetriever.getParameter(RestAPIConstants.PARAM_ca_sha1);
        if (StringUtil.isBlank(strCaSha1)) {
          throw new HttpRespAuditException(BAD_REQUEST,
              "required parameter " + RestAPIConstants.PARAM_ca_sha1 + " not specified",
              INFO, FAILED);
        }

        String strSerialNumber = httpRetriever.getParameter(
            RestAPIConstants.PARAM_serial_number);
        if (StringUtil.isBlank(strSerialNumber)) {
          throw new HttpRespAuditException(BAD_REQUEST,
               "required parameter " + RestAPIConstants.PARAM_serial_number + " not specified",
               INFO, FAILED);
        }

        if (!strCaSha1.equalsIgnoreCase(ca.getHexSha1OfCert())) {
          throw new HttpRespAuditException(BAD_REQUEST,
              "unknown " + RestAPIConstants.PARAM_ca_sha1, INFO, FAILED);
        }

        BigInteger serialNumber = toBigInt(strSerialNumber);

        if (RestAPIConstants.CMD_revoke_cert.equalsIgnoreCase(command)) {
          String strReason = httpRetriever.getParameter(RestAPIConstants.PARAM_reason);
          CrlReason reason = (strReason == null) ? CrlReason.UNSPECIFIED
              : CrlReason.forNameOrText(strReason);

          if (reason == CrlReason.REMOVE_FROM_CRL) {
            ca.unrevokeCert(serialNumber, msgId);
          } else {
            Date invalidityTime = null;
            String strInvalidityTime = httpRetriever.getParameter(
                RestAPIConstants.PARAM_invalidity_time);
            if (StringUtil.isNotBlank(strInvalidityTime)) {
              invalidityTime = DateUtil.parseUtcTimeyyyyMMddhhmmss(strInvalidityTime);
            }

            ca.revokeCert(serialNumber, reason, invalidityTime, msgId);
          }
        } else if (RestAPIConstants.CMD_delete_cert.equalsIgnoreCase(command)) {
          ca.removeCert(serialNumber, msgId);
        }
      } else if (RestAPIConstants.CMD_crl.equalsIgnoreCase(command)) {
        try {
          requestor.assertPermitted(PermissionConstants.GET_CRL);
        } catch (InsuffientPermissionException ex) {
          throw new OperationException(ErrorCode.NOT_PERMITTED, ex.getMessage());
        }

        String strCrlNumber = httpRetriever.getParameter(RestAPIConstants.PARAM_crl_number);
        BigInteger crlNumber = null;
        if (StringUtil.isNotBlank(strCrlNumber)) {
          try {
            crlNumber = toBigInt(strCrlNumber);
          } catch (NumberFormatException ex) {
            String message = "invalid crlNumber '" + strCrlNumber + "'";
            LOG.warn(message);
            throw new HttpRespAuditException(BAD_REQUEST, message, INFO, FAILED);
          }
        }

        X509CRL crl = ca.getCrl(crlNumber);
        if (crl == null) {
          String message = "could not get CRL";
          LOG.warn(message);
          throw new HttpRespAuditException(INTERNAL_SERVER_ERROR, message, INFO, FAILED);
        }

        respCt = RestAPIConstants.CT_pkix_crl;
        respBytes = crl.getEncoded();
      } else if (RestAPIConstants.CMD_new_crl.equalsIgnoreCase(command)) {
        try {
          requestor.assertPermitted(PermissionConstants.GEN_CRL);
        } catch (InsuffientPermissionException ex) {
          throw new OperationException(ErrorCode.NOT_PERMITTED, ex.getMessage());
        }

        X509CRL crl = ca.generateCrlOnDemand(msgId);
        if (crl == null) {
          String message = "could not generate CRL";
          LOG.warn(message);
          throw new HttpRespAuditException(INTERNAL_SERVER_ERROR, message, INFO, FAILED);
        }

        respCt = RestAPIConstants.CT_pkix_crl;
        respBytes = crl.getEncoded();
      } else {
        String message = "invalid command '" + command + "'";
        LOG.error(message);
        throw new HttpRespAuditException(NOT_FOUND, message, INFO, FAILED);
      }

      Map<String, String> headers = new HashMap<>();
      headers.put(RestAPIConstants.HEADER_PKISTATUS, RestAPIConstants.PKISTATUS_accepted);
      return new RestResponse(OK, respCt, headers, respBytes);
    } catch (OperationException ex) {
      ErrorCode code = ex.getErrorCode();
      if (LOG.isWarnEnabled()) {
        String msg = StringUtil.concat("generate certificate, OperationException: code=",
            code.name(), ", message=", ex.getErrorMessage());
        LogUtil.warn(LOG, ex, msg);
      }

      int sc;
      String failureInfo;
      switch (code) {
        case ALREADY_ISSUED:
          sc = BAD_REQUEST;
          failureInfo = RestAPIConstants.FAILINFO_badRequest;
          break;
        case BAD_CERT_TEMPLATE:
          sc = BAD_REQUEST;
          failureInfo = RestAPIConstants.FAILINFO_badCertTemplate;
          break;
        case BAD_REQUEST:
          sc = BAD_REQUEST;
          failureInfo = RestAPIConstants.FAILINFO_badRequest;
          break;
        case CERT_REVOKED:
          sc = CONFLICT;
          failureInfo = RestAPIConstants.FAILINFO_certRevoked;
          break;
        case CRL_FAILURE:
          sc = INTERNAL_SERVER_ERROR;
          failureInfo = RestAPIConstants.FAILINFO_systemFailure;
          break;
        case DATABASE_FAILURE:
          sc = INTERNAL_SERVER_ERROR;
          failureInfo = RestAPIConstants.FAILINFO_systemFailure;
          break;
        case NOT_PERMITTED:
          sc = UNAUTHORIZED;
          failureInfo = RestAPIConstants.FAILINFO_notAuthorized;
          break;
        case INVALID_EXTENSION:
          sc = BAD_REQUEST;
          failureInfo = RestAPIConstants.FAILINFO_badRequest;
          break;
        case SYSTEM_FAILURE:
          sc = INTERNAL_SERVER_ERROR;
          failureInfo = RestAPIConstants.FAILINFO_systemFailure;
          break;
        case SYSTEM_UNAVAILABLE:
          sc = SERVICE_UNAVAILABLE;
          failureInfo = RestAPIConstants.FAILINFO_systemUnavail;
          break;
        case UNKNOWN_CERT:
          sc = BAD_REQUEST;
          failureInfo = RestAPIConstants.FAILINFO_badCertId;
          break;
        case UNKNOWN_CERT_PROFILE:
          sc = BAD_REQUEST;
          failureInfo = RestAPIConstants.FAILINFO_badCertTemplate;
          break;
        default:
          sc = INTERNAL_SERVER_ERROR;
          failureInfo = RestAPIConstants.FAILINFO_systemFailure;
          break;
      } // end switch (code)

      event.setStatus(AuditStatus.FAILED);
      event.addEventData(CaAuditConstants.NAME_message, code.name());

      switch (code) {
        case DATABASE_FAILURE:
        case SYSTEM_FAILURE:
          auditMessage = code.name();
          break;
        default:
          auditMessage = code.name() + ": " + ex.getErrorMessage();
          break;
      } // end switch code

      Map<String, String> headers = new HashMap<>();
      headers.put(RestAPIConstants.HEADER_PKISTATUS, RestAPIConstants.PKISTATUS_rejection);

      if (StringUtil.isNotBlank(failureInfo)) {
        headers.put(RestAPIConstants.HEADER_failInfo, failureInfo);
      }
      return new RestResponse(sc, null, headers, null);
    } catch (HttpRespAuditException ex) {
      auditStatus = ex.getAuditStatus();
      auditLevel = ex.getAuditLevel();
      auditMessage = ex.getAuditMessage();
      return new RestResponse(ex.getHttpStatus(), null, null, null);
    } catch (Throwable th) {
      if (th instanceof EOFException) {
        LogUtil.warn(LOG, th, "connection reset by peer");
      } else {
        LOG.error("Throwable thrown, this should not happen!", th);
      }
      auditLevel = AuditLevel.ERROR;
      auditStatus = AuditStatus.FAILED;
      auditMessage = "internal error";
      return new RestResponse(INTERNAL_SERVER_ERROR, null, null, null);
    } finally {
      event.setStatus(auditStatus);
      event.setLevel(auditLevel);
      if (auditMessage != null) {
        event.addEventData(CaAuditConstants.NAME_message, auditMessage);
      }
    }
  } // method service

  private static BigInteger toBigInt(String str) {
    String tmpStr = str.trim();
    if (tmpStr.startsWith("0x") || tmpStr.startsWith("0X")) {
      if (tmpStr.length() > 2) {
        return new BigInteger(tmpStr.substring(2), 16);
      } else {
        throw new NumberFormatException("invalid integer '" + tmpStr + "'");
      }
    }
    return new BigInteger(tmpStr);
  } // method toBigInt

}
