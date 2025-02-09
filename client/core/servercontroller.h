#ifndef SERVERCONTROLLER_H
#define SERVERCONTROLLER_H

#include <QJsonObject>
#include <QObject>
#include "sshconnection.h"
#include "sshremoteprocess.h"
#include "debug.h"
#include "defs.h"
#include "settings.h"

#include "containers/containers_defs.h"

#include "sftpdefs.h"

using namespace amnezia;

class ServerController : public QObject
{
    Q_OBJECT
public:
    typedef QList<QPair<QString, QString>> Vars;

    static ErrorCode fromSshConnectionErrorCode(QSsh::SshError error);

    // QSsh exitCode and exitStatus are different things
    static ErrorCode fromSshProcessExitStatus(int exitStatus);

    static QSsh::SshConnectionParameters sshParams(const ServerCredentials &credentials);
    static void disconnectFromHost(const ServerCredentials &credentials);

    static ErrorCode removeAllContainers(const ServerCredentials &credentials);
    static ErrorCode removeContainer(const ServerCredentials &credentials, DockerContainer container);
    static ErrorCode setupContainer(const ServerCredentials &credentials, DockerContainer container, QJsonObject &config);
    static ErrorCode updateContainer(const ServerCredentials &credentials, DockerContainer container,
        const QJsonObject &oldConfig, QJsonObject &newConfig);

    // create initial config - generate passwords, etc
    static QJsonObject createContainerInitialConfig(DockerContainer container, int port, TransportProto tp);

    static bool isReinstallContainerRequred(DockerContainer container, const QJsonObject &oldConfig, const QJsonObject &newConfig);

    static ErrorCode checkOpenVpnServer(DockerContainer container, const ServerCredentials &credentials);

    static ErrorCode uploadFileToHost(const ServerCredentials &credentials, const QByteArray &data,
        const QString &remotePath, QSsh::SftpOverwriteMode overwriteMode = QSsh::SftpOverwriteMode::SftpOverwriteExisting);

    static ErrorCode uploadTextFileToContainer(DockerContainer container,
        const ServerCredentials &credentials, const QString &file, const QString &path,
        QSsh::SftpOverwriteMode overwriteMode = QSsh::SftpOverwriteMode::SftpOverwriteExisting);

    static QByteArray getTextFileFromContainer(DockerContainer container,
        const ServerCredentials &credentials, const QString &path, ErrorCode *errorCode = nullptr);

    static ErrorCode setupServerFirewall(const ServerCredentials &credentials);

    static QString replaceVars(const QString &script, const Vars &vars);

    static ErrorCode runScript(const ServerCredentials &credentials, QString script,
        const std::function<void(const QString &, QSharedPointer<QSsh::SshRemoteProcess>)> &cbReadStdOut = nullptr,
        const std::function<void(const QString &, QSharedPointer<QSsh::SshRemoteProcess>)> &cbReadStdErr = nullptr);

    static ErrorCode runContainerScript(const ServerCredentials &credentials, DockerContainer container, QString script,
        const std::function<void(const QString &, QSharedPointer<QSsh::SshRemoteProcess>)> &cbReadStdOut = nullptr,
        const std::function<void(const QString &, QSharedPointer<QSsh::SshRemoteProcess>)> &cbReadStdErr = nullptr);

    static Vars genVarsForScript(const ServerCredentials &credentials, DockerContainer container = DockerContainer::None, const QJsonObject &config = QJsonObject());

    static QString checkSshConnection(const ServerCredentials &credentials, ErrorCode *errorCode = nullptr);
    static QSsh::SshConnection *connectToHost(const QSsh::SshConnectionParameters &sshParams);

private:

    static ErrorCode installDockerWorker(const ServerCredentials &credentials, DockerContainer container);
    static ErrorCode prepareHostWorker(const ServerCredentials &credentials, DockerContainer container, const QJsonObject &config = QJsonObject());
    static ErrorCode buildContainerWorker(const ServerCredentials &credentials, DockerContainer container, const QJsonObject &config = QJsonObject());
    static ErrorCode runContainerWorker(const ServerCredentials &credentials, DockerContainer container, QJsonObject &config);
    static ErrorCode configureContainerWorker(const ServerCredentials &credentials, DockerContainer container, QJsonObject &config);
    static ErrorCode startupContainerWorker(const ServerCredentials &credentials, DockerContainer container, const QJsonObject &config = QJsonObject());

    static Settings &m_settings();
};

#endif // SERVERCONTROLLER_H
