#include "shadowsocksvpnprotocol.h"
#include "core/servercontroller.h"

#include "debug.h"
#include "utils.h"

#include <QCryptographicHash>
#include <QJsonDocument>
#include <QJsonObject>

ShadowSocksVpnProtocol::ShadowSocksVpnProtocol(const QJsonObject &configuration, QObject *parent):
    OpenVpnProtocol(configuration, parent)
{
    readShadowSocksConfiguration(configuration);
}

ShadowSocksVpnProtocol::~ShadowSocksVpnProtocol()
{
    qDebug() << "ShadowSocksVpnProtocol::~ShadowSocksVpnProtocol";
    ShadowSocksVpnProtocol::stop();
    QThread::msleep(200);
    m_ssProcess.close();
}

ErrorCode ShadowSocksVpnProtocol::start()
{
    if (Utils::processIsRunning(Utils::executable("ss-local", false))) {
        Utils::killProcessByName(Utils::executable("ss-local", false));
    }

#ifdef QT_DEBUG
    m_shadowSocksCfgFile.setAutoRemove(false);
#endif
    m_shadowSocksCfgFile.open();
    m_shadowSocksCfgFile.write(QJsonDocument(m_shadowSocksConfig).toJson());
    m_shadowSocksCfgFile.close();

    QStringList args = QStringList() << "-c" << m_shadowSocksCfgFile.fileName()
                                     << "--no-delay";

    qDebug().noquote() << "ShadowSocksVpnProtocol::start()"
                       << shadowSocksExecPath() << args.join(" ");

    m_ssProcess.setProcessChannelMode(QProcess::MergedChannels);

    m_ssProcess.setProgram(shadowSocksExecPath());
    m_ssProcess.setArguments(args);

    connect(&m_ssProcess, &QProcess::readyReadStandardOutput, this, [this](){
        qDebug().noquote() << "ss-local:" << m_ssProcess.readAllStandardOutput();
    });

    connect(&m_ssProcess, QOverload<int, QProcess::ExitStatus>::of(&QProcess::finished), this, [this](int exitCode, QProcess::ExitStatus exitStatus){
        qDebug().noquote() << "ShadowSocksVpnProtocol finished, exitCode, exiStatus" << exitCode << exitStatus;
        setConnectionState(VpnProtocol::ConnectionState::Disconnected);
        if (exitStatus != QProcess::NormalExit){
            emit protocolError(amnezia::ErrorCode::ShadowSocksExecutableCrashed);
            stop();
        }
        if (exitCode !=0 ){
            emit protocolError(amnezia::ErrorCode::InternalError);
            stop();
        }
    });

    m_ssProcess.start();
    m_ssProcess.waitForStarted();

    if (m_ssProcess.state() == QProcess::ProcessState::Running) {
        setConnectionState(ConnectionState::Connecting);

        return OpenVpnProtocol::start();
    }
    else return ErrorCode::ShadowSocksExecutableMissing;
}

void ShadowSocksVpnProtocol::stop()
{
    OpenVpnProtocol::stop();

    qDebug() << "ShadowSocksVpnProtocol::stop()";
    m_ssProcess.terminate();

#ifdef Q_OS_WIN
    Utils::signalCtrl(m_ssProcess.processId(), CTRL_C_EVENT);
#endif
}

QString ShadowSocksVpnProtocol::shadowSocksExecPath()
{
#ifdef Q_OS_WIN
    return Utils::executable(QString("ss/ss-local"), true);
#else
    return Utils::executable(QString("/ss-local"), true);
#endif
}

QJsonObject ShadowSocksVpnProtocol::genShadowSocksConfig(const ServerCredentials &credentials, Protocol proto)
{
    QJsonObject ssConfig;
    ssConfig.insert("server", credentials.hostName);
    ssConfig.insert("server_port", ServerController::ssRemotePort());
    ssConfig.insert("local_port", ServerController::ssContainerPort());
    ssConfig.insert("password", QString(QCryptographicHash::hash(credentials.password.toUtf8(), QCryptographicHash::Sha256).toHex()));
    ssConfig.insert("timeout", 60);
    ssConfig.insert("method", ServerController::ssEncryption());
    return ssConfig;
}

void ShadowSocksVpnProtocol::readShadowSocksConfiguration(const QJsonObject &configuration)
{
    m_shadowSocksConfig = configuration.value(config::key_shadowsocks_config_data()).toObject();
}
