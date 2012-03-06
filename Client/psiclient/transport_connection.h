/*
 * Copyright (c) 2012, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

#pragma once

#include "sessioninfo.h"
#include "systemproxysettings.h"
#include "utilities.h"
#include "worker_thread.h"

class LocalProxy;
class ILocalProxyStatsCollector;
class ITransport;


/*
This class encapsulates many of the steps required to set up, run, and tear
down a transport connection (including local proxy).
Basically a convenience wrapper for Transport+LocalProxy+handshake.
This class is also responsible for the handshake.
*/
class TransportConnection
{
public:
    TransportConnection();
    virtual ~TransportConnection();

    /*
    May throw the same exceptions as ITransport::Connect and WorkerThread::Start,
    but *not* ITransport::TransportFailed. Also throws TryNextServer, in 
    the case of a normal failure.
    If statsCollector is null, then stats will not be collected.
    If handshakeRequestPath is null, then no handshake will be done. (This 
    means that transports that require a pre-handshake will fail, and others
    will have no following handshake. This should only be the case for 
    tempoary connections.)
    */
    void Connect(
            ITransport* transport,
            ILocalProxyStatsCollector* statsCollector, 
            const SessionInfo& sessionInfo, 
            const TCHAR* handshakeRequestPath,
            const tstring& splitTunnelingFilePath, 
            const bool& stopSignalFlag);

    // Blocks until the transport disconnects.
    void WaitForDisconnect();

    // When a connection is made, a handshake is done to get extra information
    // from the server. That info can be retrieved with this function.
    SessionInfo GetUpdatedSessionInfo() const;

    // Exception class
    class TryNextServer { };

private:
    bool DoHandshake(const TCHAR* handshakeRequestPath, const bool& stopSignalFlag);
    void Cleanup();

private:
    ITransport* m_transport;
    LocalProxy* m_localProxy;
    SessionInfo m_sessionInfo;
    SystemProxySettings m_systemProxySettings;
    WorkerThreadSynch m_workerThreadSynch;
};
