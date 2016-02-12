/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at legal-notices/CDDLv1_0.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *      Copyright 2016 ForgeRock AS
 */
package org.opends.server.tools;

import static org.forgerock.opendj.adapter.server3x.Converters.*;
import static org.forgerock.opendj.ldap.requests.Requests.*;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.opendj.ldap.ByteString;
import org.forgerock.opendj.ldap.LdapException;
import org.forgerock.opendj.ldap.ResultCode;
import org.forgerock.opendj.ldap.SearchScope;
import org.forgerock.opendj.ldap.controls.Control;
import org.forgerock.opendj.ldap.requests.AddRequest;
import org.forgerock.opendj.ldap.requests.CompareRequest;
import org.forgerock.opendj.ldap.requests.DeleteRequest;
import org.forgerock.opendj.ldap.requests.ModifyDNRequest;
import org.forgerock.opendj.ldap.requests.ModifyRequest;
import org.forgerock.opendj.ldap.requests.SearchRequest;
import org.forgerock.opendj.ldap.requests.SimpleBindRequest;
import org.opends.admin.ads.util.BlindTrustManager;
import org.opends.server.TestCaseUtils;
import org.opends.server.protocols.ldap.AddRequestProtocolOp;
import org.opends.server.protocols.ldap.AddResponseProtocolOp;
import org.opends.server.protocols.ldap.BindRequestProtocolOp;
import org.opends.server.protocols.ldap.BindResponseProtocolOp;
import org.opends.server.protocols.ldap.CompareRequestProtocolOp;
import org.opends.server.protocols.ldap.CompareResponseProtocolOp;
import org.opends.server.protocols.ldap.DeleteRequestProtocolOp;
import org.opends.server.protocols.ldap.DeleteResponseProtocolOp;
import org.opends.server.protocols.ldap.LDAPMessage;
import org.opends.server.protocols.ldap.ModifyDNRequestProtocolOp;
import org.opends.server.protocols.ldap.ModifyDNResponseProtocolOp;
import org.opends.server.protocols.ldap.ModifyRequestProtocolOp;
import org.opends.server.protocols.ldap.ModifyResponseProtocolOp;
import org.opends.server.protocols.ldap.ProtocolOp;
import org.opends.server.protocols.ldap.SearchRequestProtocolOp;
import org.opends.server.protocols.ldap.SearchResultDoneProtocolOp;
import org.opends.server.protocols.ldap.SearchResultEntryProtocolOp;
import org.opends.server.types.LDAPException;

/** Modeled like an SDK Connection, but implemented using the servers' ProtocolOp classes */
@SuppressWarnings("javadoc")
public final class RemoteConnection implements Closeable
{
  private final Socket socket;
  private LDAPReader r;
  private LDAPWriter w;
  private int messageID;

  public RemoteConnection(String host, int port) throws Exception
  {
    this(host, port, false);
  }

  public RemoteConnection(String host, int port, boolean secure) throws Exception
  {
    socket = secure ? getSslSocket(host, port) : new Socket(host, port);
    r = new LDAPReader(socket);
    w = new LDAPWriter(socket);
    TestCaseUtils.configureSocket(socket);
  }

  private Socket getSslSocket(String host, int port) throws Exception
  {
    SSLContext sslCtx = SSLContext.getInstance("TLSv1");
    TrustManager[] tm = new TrustManager[] { new BlindTrustManager() };
    sslCtx.init(null, tm, new SecureRandom());
    SSLSocketFactory socketFactory = sslCtx.getSocketFactory();
    return socketFactory.createSocket(host, port);
  }

  public LDAPMessage bind(SimpleBindRequest bindRequest) throws IOException, LDAPException, LdapException
  {
    return bind(bindRequest, true);
  }

  public LDAPMessage bind(SimpleBindRequest bindRequest, boolean throwOnExceptionalResultCode) throws IOException,
      LDAPException, LdapException
  {
    return bind(bindRequest.getName(), bindRequest.getPassword(), throwOnExceptionalResultCode, bindRequest
        .getControls());
  }

  public LDAPMessage bind(String bindDN, String bindPassword, Control... controls)
      throws IOException, LDAPException, LdapException
  {
    return bind(bindDN, bindPassword.getBytes(), true, Arrays.asList(controls));
  }

  private LDAPMessage bind(String bindDN, byte[] bindPassword, boolean throwOnExceptionalResultCode,
      List<Control> controls) throws IOException, LDAPException, LdapException
  {
    writeMessage(new BindRequestProtocolOp(bs(bindDN), 3, bs(bindPassword)), to(controls));
    LDAPMessage message = r.readMessage();
    if (throwOnExceptionalResultCode)
    {
      BindResponseProtocolOp response = message.getBindResponseProtocolOp();
      return validateNoException(message, response.getResultCode(), response.getErrorMessage());
    }
    return message;
  }

  public LDAPMessage add(AddRequest addRequest) throws IOException, LDAPException, LdapException
  {
    return add(addRequest, true);
  }

  public LDAPMessage add(AddRequest addRequest, boolean throwOnExceptionalResultCode) throws IOException,
      LDAPException, LdapException
  {
    writeMessage(addProtocolOp(addRequest), to(addRequest.getControls()));
    LDAPMessage message = r.readMessage();
    if (throwOnExceptionalResultCode)
    {
      AddResponseProtocolOp response = message.getAddResponseProtocolOp();
      return validateNoException(message, response.getResultCode(), response.getErrorMessage());
    }
    return message;
  }

  private AddRequestProtocolOp addProtocolOp(AddRequest add)
  {
    return new AddRequestProtocolOp(bs(add.getName()), to(add.getAllAttributes()));
  }

  public void search(String baseDN, SearchScope scope, String filterString, String... attributes) throws IOException,
      LDAPException
  {
    search(newSearchRequest(baseDN, scope, filterString, attributes));
  }

  public void search(SearchRequest searchRequest) throws IOException, LDAPException, LdapException
  {
    writeMessage(searchProtocolOp(searchRequest), to(searchRequest.getControls()));
  }

  private SearchRequestProtocolOp searchProtocolOp(SearchRequest r) throws LDAPException
  {
    return new SearchRequestProtocolOp(bs(r.getName()), r.getScope(), r.getDereferenceAliasesPolicy(),
        r.getSizeLimit(), r.getTimeLimit(), r.isTypesOnly(), to(r.getFilter()), new LinkedHashSet<>(r.getAttributes()));
  }

  public List<SearchResultEntryProtocolOp> readEntries() throws LDAPException, IOException
  {
    List<SearchResultEntryProtocolOp> entries = new ArrayList<>();
    LDAPMessage msg;
    while ((msg = r.readMessage()) != null)
    {
      ProtocolOp protocolOp = msg.getProtocolOp();
      if (protocolOp instanceof SearchResultDoneProtocolOp)
      {
        SearchResultDoneProtocolOp done = (SearchResultDoneProtocolOp) protocolOp;
        validateNoException(msg, done.getResultCode(), done.getErrorMessage());
        return entries;
      }
      else if (protocolOp instanceof SearchResultEntryProtocolOp)
      {
        entries.add((SearchResultEntryProtocolOp) protocolOp);
      }
      else
      {
        throw new RuntimeException("Unexpected message " + protocolOp);
      }
    }
    return entries;
  }

  public LDAPMessage modify(ModifyRequest modifyRequest) throws IOException, LDAPException, LdapException
  {
    return modify(modifyRequest, true);
  }

  public LDAPMessage modify(ModifyRequest modifyRequest, boolean throwOnExceptionalResultCode)
      throws IOException, LDAPException, LdapException
  {
    writeMessage(modifyProtocolOp(modifyRequest), to(modifyRequest.getControls()));
    LDAPMessage message = r.readMessage();
    if (throwOnExceptionalResultCode)
    {
      ModifyResponseProtocolOp response = message.getModifyResponseProtocolOp();
      return validateNoException(message, response.getResultCode(), response.getErrorMessage());
    }
    return message;
  }

  private ProtocolOp modifyProtocolOp(ModifyRequest r)
  {
    return new ModifyRequestProtocolOp(bs(r.getName()), toRawModifications(r.getModifications()));
  }

  public ModifyDNResponseProtocolOp modifyDN(String entryDN, String newRDN, boolean deleteOldRDN)
      throws IOException, LDAPException, LdapException
  {
    writeMessage(new ModifyDNRequestProtocolOp(bs(entryDN), bs(newRDN), deleteOldRDN));
    return r.readMessage().getModifyDNResponseProtocolOp();
  }

  public LDAPMessage modifyDN(ModifyDNRequest modifyDNRequest) throws IOException, LDAPException, LdapException
  {
    return modifyDN(modifyDNRequest, true);
  }

  public LDAPMessage modifyDN(ModifyDNRequest modifyDNRequest, boolean throwOnExceptionalResultCode)
      throws IOException, LDAPException, LdapException
  {
    writeMessage(modDNProtocolOp(modifyDNRequest), to(modifyDNRequest.getControls()));
    LDAPMessage message = r.readMessage();
    if (throwOnExceptionalResultCode)
    {
      ModifyDNResponseProtocolOp response = message.getModifyDNResponseProtocolOp();
      return validateNoException(message, response.getResultCode(), response.getErrorMessage());
    }
    return message;
  }

  private ModifyDNRequestProtocolOp modDNProtocolOp(ModifyDNRequest r)
  {
    return new ModifyDNRequestProtocolOp(bs(r.getName()), bs(r.getNewRDN()), r.isDeleteOldRDN(), bs(r.getNewSuperior()));
  }

  public LDAPMessage compare(CompareRequest compareRequest, boolean throwOnExceptionalResultCode) throws IOException,
      LDAPException, LdapException
  {
    writeMessage(compareProtocolOp(compareRequest), to(compareRequest.getControls()));
    LDAPMessage message = r.readMessage();
    if (throwOnExceptionalResultCode)
    {
      CompareResponseProtocolOp response = message.getCompareResponseProtocolOp();
      return validateNoException(message, response.getResultCode(), response.getErrorMessage());
    }
    return message;
  }

  private CompareRequestProtocolOp compareProtocolOp(CompareRequest r)
  {
    return new CompareRequestProtocolOp(bs(r.getName()), r.getAttributeDescription().toString(), r.getAssertionValue());
  }

  public LDAPMessage delete(DeleteRequest deleteRequest) throws IOException, LDAPException, LdapException
  {
    return delete(deleteRequest, true);
  }

  public LDAPMessage delete(DeleteRequest deleteRequest, boolean throwOnExceptionalResultCode) throws IOException,
      LDAPException, LdapException
  {
    writeMessage(new DeleteRequestProtocolOp(bs(deleteRequest.getName())), to(deleteRequest.getControls()));
    LDAPMessage message = r.readMessage();
    if (throwOnExceptionalResultCode)
    {
      DeleteResponseProtocolOp response = message.getDeleteResponseProtocolOp();
      return validateNoException(message, response.getResultCode(), response.getErrorMessage());
    }
    return message;
  }

  private ByteString bs(Object o)
  {
    return o != null ? ByteString.valueOfObject(o) : null;
  }

  public void writeMessage(ProtocolOp protocolOp) throws IOException
  {
    writeMessage(protocolOp, null);
  }

  public void writeMessage(ProtocolOp protocolOp, List<org.opends.server.types.Control> controls) throws IOException
  {
    w.writeMessage(new LDAPMessage(++messageID, protocolOp, controls));
  }

  public LDAPMessage readMessage() throws IOException, LDAPException
  {
    return r.readMessage();
  }

  private LDAPMessage validateNoException(LDAPMessage message, int resultCode, LocalizableMessage errorMessage)
      throws LdapException
  {
    ResultCode rc = ResultCode.valueOf(resultCode);
    if (rc.isExceptional())
    {
      throw LdapException.newLdapException(rc, errorMessage);
    }
    return message;
  }

  @Override
  public void close() throws IOException
  {
    socket.close();
  }
}