#!/usr/bin/env python3
#
# Electrum - lightweight Bitcoin client
# Copyright (C) 2011 thomasv@gitorious
#
# Permission is hereby granted, free of charge, to any person
# obtaining a copy of this software and associated documentation files
# (the "Software"), to deal in the Software without restriction,
# including without limitation the rights to use, copy, modify, merge,
# publish, distribute, sublicense, and/or sell copies of the Software,
# and to permit persons to whom the Software is furnished to do so,
# subject to the following conditions:
#
# The above copyright notice and this permission notice shall be
# included in all copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
# EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
# MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
# NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
# BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
# ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
# CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.
import os
import re
import socket
import ssl
import sys
import threading
import time
import traceback

import requests

from .util import print_error

ca_path = requests.certs.where()

from . import util
from . import x509
from . import pem


def Connection(server, queue, config_path):
    """Makes asynchronous connections to a remote electrum server.
    Returns the running thread that is making the connection.

    Once the thread has connected, it finishes, placing a tuple on the
    queue of the form (server, socket), where socket is None if
    connection failed.
    """
    host, port, protocol = server.rsplit(':', 2)
    if not protocol in 'st':
        raise Exception('Unknown protocol: %s' % protocol)
    c = TcpConnection(server, queue, config_path)
    c.start()
    return c


class TcpConnection(threading.Thread, util.PrintError):

    def __init__(self, server, queue, config_path):
        threading.Thread.__init__(self)
        self.config_path = config_path
        self.queue = queue
        self.server = server
        self.host, self.port, self.protocol = self.server.rsplit(':', 2)
        self.host = str(self.host)
        self.port = int(self.port)
        self.use_ssl = (self.protocol == 's')
        self.daemon = True

    def diagnostic_name(self):
        return self.host

    def check_host_name(self, peercert, name):
        """Simple certificate/host name checker.  Returns True if the
        certificate matches, False otherwise.  Does not support
        wildcards."""
        # Check that the peer has supplied a certificate.
        # None/{} is not acceptable.
        if not peercert:
            return False
        if 'subjectAltName' in peercert:
            for typ, val in peercert["subjectAltName"]:
                if typ == "DNS" and val == name:
                    return True
        else:
            # Only check the subject DN if there is no subject alternative
            # name.
            cn = None
            for attr, val in peercert["subject"]:
                # Use most-specific (last) commonName attribute.
                if attr == "commonName":
                    cn = val
            if cn is not None:
                return cn == name
        return False

    def get_simple_socket(self):
        try:
            l = socket.getaddrinfo(self.host, self.port, socket.AF_UNSPEC, socket.SOCK_STREAM)
        except OverflowError:
            # This can happen if user specifies a huge port out of 32-bit range. See #985
            self.print_error("port invalid:", self.port)
            return
        except socket.gaierror:
            self.print_error("cannot resolve hostname")
            return
        except UnicodeError:
            self.print_error("hostname cannot be decoded with 'idna' codec")
            return
        e = None
        for res in l:
            try:
                s = socket.socket(res[0], socket.SOCK_STREAM)
                s.settimeout(10)
                s.connect(res[4])
                s.settimeout(2)
                s.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
                return s
            except BaseException as _e:
                e = _e
                continue
        else:
            self.print_error("failed to connect", str(e))

    @staticmethod
    def get_ssl_context(cert_reqs, ca_certs):
        context = ssl.create_default_context(purpose=ssl.Purpose.SERVER_AUTH, cafile=ca_certs)
        context.check_hostname = False
        context.verify_mode = cert_reqs

        context.options |= ssl.OP_NO_SSLv2
        context.options |= ssl.OP_NO_SSLv3
        context.options |= ssl.OP_NO_TLSv1

        return context

    def get_socket(self):
        if self.use_ssl:
            cert_path = os.path.join(self.config_path, 'certs', self.host)
            if not os.path.exists(cert_path):
                is_new = True
                s = self.get_simple_socket()
                if s is None:
                    return
                # try with CA first
                try:
                    context = self.get_ssl_context(cert_reqs=ssl.CERT_REQUIRED, ca_certs=ca_path)
                    s = context.wrap_socket(s, do_handshake_on_connect=True)
                except ssl.SSLError as e:
                    self.print_error(e)
                    s = None
                except:
                    return

                if s and self.check_host_name(s.getpeercert(), self.host):
                    self.print_error("SSL certificate signed by CA")
                    return s
                # get server certificate.
                # Do not use ssl.get_server_certificate because it does not work with proxy
                s = self.get_simple_socket()
                if s is None:
                    return
                try:
                    context = self.get_ssl_context(cert_reqs=ssl.CERT_NONE, ca_certs=None)
                    s = context.wrap_socket(s)
                except ssl.SSLError as e:
                    self.print_error("SSL error retrieving SSL certificate:", e)
                    return
                except:
                    return

                dercert = s.getpeercert(True)
                s.close()
                cert = ssl.DER_cert_to_PEM_cert(dercert)
                # workaround android bug
                cert = re.sub("([^\n])-----END CERTIFICATE-----","\\1\n-----END CERTIFICATE-----",cert)
                temporary_path = cert_path + '.temp'
                util.assert_datadir_available(self.config_path)
                with open(temporary_path, "w", encoding='utf-8') as f:
                    f.write(cert)
                    f.flush()
                    os.fsync(f.fileno())
            else:
                is_new = False

        s = self.get_simple_socket()
        if s is None:
            return

        if self.use_ssl:
            try:
                context = self.get_ssl_context(cert_reqs=ssl.CERT_REQUIRED,
                                               ca_certs=(temporary_path if is_new else cert_path))
                s = context.wrap_socket(s, do_handshake_on_connect=True)
            except socket.timeout:
                self.print_error('timeout')
                return
            except ssl.SSLError as e:
                self.print_error("SSL error:", e)
                if e.errno != 1:
                    return
                if is_new:
                    rej = cert_path + '.rej'
                    if os.path.exists(rej):
                        os.unlink(rej)
                    os.rename(temporary_path, rej)
                else:
                    util.assert_datadir_available(self.config_path)
                    with open(cert_path, encoding='utf-8') as f:
                        cert = f.read()
                    try:
                        b = pem.dePem(cert, 'CERTIFICATE')
                        x = x509.X509(b)
                    except:
                        if util.is_verbose:
                            self.print_error("Error checking certificate, traceback follows")
                            traceback.print_exc(file=sys.stderr)
                        self.print_error("wrong certificate")
                        return
                    try:
                        x.check_date()
                    except:
                        self.print_error("certificate has expired:", cert_path)
                        os.unlink(cert_path)
                        return
                    self.print_error("wrong certificate")
                if e.errno == 104:
                    return
                return

            if is_new:
                self.print_error("saving certificate")
                os.rename(temporary_path, cert_path)

        return s

    def run(self):
        try:
            socket = self.get_socket()
        except OSError:
            if util.is_verbose:
                self.print_error("Error getting socket, traceback follows")
                traceback.print_exc(file=sys.stderr)
            socket = None

        if socket:
            self.print_error("connected")
        self.queue.put((self.server, socket))


class Interface(util.PrintError):
    """The Interface class handles a socket connected to a single remote
    electrum server.  It's exposed API is:

    - Member functions close(), fileno(), get_responses(), has_timed_out(),
      ping_required(), queue_request(), send_requests()
    - Member variable server.
    """

    MODE_DEFAULT = 'default'
    MODE_BACKWARD = 'backward'
    MODE_BINARY = 'binary'
    MODE_CATCH_UP = 'catch_up'
    MODE_VERIFICATION = 'verification'

    def __init__(self, server, socket, *, max_message_bytes=0):
        self.server = server
        self.host, self.port, _ = server.rsplit(':', 2)
        self.socket = socket

        self.pipe = util.SocketPipe(socket, max_message_bytes=max_message_bytes)
        self.pipe.set_timeout(0.0)  # Don't wait for data
        # Dump network messages.  Set at runtime from the console.
        self.debug = False
        self.unsent_requests = []
        self.unanswered_requests = {}
        self.last_send = time.time()
        self.closed_remotely = False

        self.mode = None

    def __repr__(self):
        return "<{}.{} {}>".format(__name__, type(self).__name__, self.format_address())

    def format_address(self):
        return "{}:{}".format(self.host, self.port)

    def set_mode(self, mode):
        self.print_error("set_mode({})".format(mode))
        self.mode = mode

    def diagnostic_name(self):
        return self.host

    def fileno(self):
        # Needed for select
        return self.socket.fileno()

    def close(self):
        if not self.closed_remotely:
            try:
                self.socket.shutdown(socket.SHUT_RDWR)
            except socket.error:
                pass
        self.socket.close()
        self.pipe.clean_up()

    def queue_request(self, *args):  # method, params, _id
        '''Queue a request, later to be sent with send_requests when the
        socket is available for writing.
        '''
        self.request_time = time.time()
        self.unsent_requests.append(args)

    def num_requests(self):
        '''Keep unanswered requests below 100'''
        n = 100 - len(self.unanswered_requests)
        return min(n, len(self.unsent_requests))

    def send_requests(self):
        '''Sends queued requests.  Returns False on failure.'''
        self.last_send = time.time()
        make_dict = lambda m, p, i: {'method': m, 'params': p, 'id': i}
        n = self.num_requests()
        wire_requests = self.unsent_requests[0:n]
        try:
            self.pipe.send_all([make_dict(*r) for r in wire_requests])
        except (OSError, ssl.SSLError) as e:
            self.print_error("send_requests: {}: {}".format(type(e).__name__, e))
            return False
        self.unsent_requests = self.unsent_requests[n:]
        for request in wire_requests:
            if self.debug:
                self.print_error("-->", request)
            self.unanswered_requests[request[2]] = request
        return True

    def ping_required(self):
        '''Returns True if a ping should be sent.'''
        return time.time() - self.last_send > 300

    def has_timed_out(self):
        '''Returns True if the interface has timed out.'''
        if (self.unanswered_requests and time.time() - self.request_time > 10
            and self.pipe.idle_time() > 10):
            self.print_error("timeout", len(self.unanswered_requests))
            return True

        return False

    def get_responses(self):
        '''Call if there is data available on the socket.  Returns a list of
        (request, response) pairs.  Notifications are singleton
        unsolicited responses presumably as a result of prior
        subscriptions, so request is None and there is no 'id' member.
        Otherwise it is a response, which has an 'id' member and a
        corresponding request.  If the connection was closed remotely
        or the remote server is misbehaving, a (None, None) will appear.
        '''
        responses = []
        while True:
            try:
                response = self.pipe.get()
            except util.timeout:
                break
            except self.pipe.MessageSizeExceeded as e:
                self.print_error(repr(e))
                responses.append((None, None))  # signals Network class to close this connection
                break
            if not type(response) is dict:
                responses.append((None, None))
                if response is None:
                    self.closed_remotely = True
                    self.print_error("connection closed remotely")
                break
            if self.debug:
                self.print_error("<--", response)
            wire_id = response.get('id', None)
            if wire_id is None:  # Notification
                responses.append((None, response))
            else:
                request = self.unanswered_requests.pop(wire_id, None)
                if request:
                    responses.append((request, response))
                else:
                    self.print_error("unknown wire ID", wire_id)
                    responses.append((None, None)) # Signal
                    break

        return responses


def check_cert(host, cert):
    try:
        b = pem.dePem(cert, 'CERTIFICATE')
        x = x509.X509(b)
    except:
        if util.is_verbose:
            print_error("Error checking certificate, traceback follows")
            traceback.print_exc(file=sys.stderr)
        return

    try:
        x.check_date()
        expired = False
    except:
        expired = True

    m = "host: %s\n"%host
    m += "has_expired: %s\n"% expired
    util.print_msg(m)


# Used by tests
def _match_hostname(name, val):
    if val == name:
        return True

    return val.startswith('*.') and name.endswith(val[1:])


def test_certificates():
    from .simple_config import SimpleConfig
    config = SimpleConfig()
    mydir = os.path.join(config.path, "certs")
    certs = os.listdir(mydir)
    for c in certs:
        p = os.path.join(mydir,c)
        with open(p, encoding='utf-8') as f:
            cert = f.read()
        check_cert(c, cert)

if __name__ == "__main__":
    test_certificates()
