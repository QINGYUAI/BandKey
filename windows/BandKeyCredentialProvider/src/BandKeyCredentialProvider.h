#pragma once

#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include <windows.h>
#include <credentialprovider.h>
#include <initguid.h>

// 与安装器注册表、命名管道约定保持一致
// {E86A4C6E-5B8A-4C2D-9F3A-1B2C3D4E5F60}
DEFINE_GUID(
    CLSID_BandKeyCredentialProvider,
    0xe86a4c6e, 0x5b8a, 0x4c2d, 0x9f, 0x3a, 0x1b, 0x2c, 0x3d, 0x4e, 0x5f, 0x60);

class BandKeyCredentialProvider;
class BandKeyCredential;

// 凭据 tile 字段。LogonUI 会按 GetFieldDescriptorCount/GetFieldDescriptorAt
// 返回的描述符绘制，GetFieldState/GetStringValue 等必须对同一组 ID 给出响应。
enum BandKeyFieldId
{
    BKF_STATUS = 0,
    BKF_SUBMIT = 1,
    BKF_COUNT
};

// 凭据提供程序诊断日志（写入 C:\ProgramData\BandKey\cp.log）。
void CpLog(const char* format, ...);

// 命名管道客户端：连接 BandKeyService 的 \\.\pipe\BandKeyPipe。
// 接收 UNLOCK_READY / AUTO_UNLOCK，并回写 AUTO_UNLOCK_ACK / PASSWORD_FAILED。
class PipeClient
{
public:
    PipeClient(BandKeyCredential& owner);
    ~PipeClient();

    void Start();
    void Stop();
    bool SendLine(const std::string& line);

private:
    void Run();
    void HandleLine(const std::string& line);

    BandKeyCredential& owner_;
    HANDLE stopEvent_ = nullptr;
    HANDLE thread_ = nullptr;
    HANDLE pipe_ = nullptr;
    std::mutex writeMutex_;
};

// 从磁盘读取 DPAPI 加密的登录凭据（由托盘/设置程序写入）。
class StoredCredential
{
public:
    bool Load(std::wstring& username, std::wstring& password);
};

// 主凭据提供程序
class BandKeyCredentialProvider : public ICredentialProvider
{
public:
    BandKeyCredentialProvider();

    // IUnknown
    IFACEMETHODIMP QueryInterface(REFIID riid, void** ppv) override;
    IFACEMETHODIMP_(ULONG) AddRef() override;
    IFACEMETHODIMP_(ULONG) Release() override;

    // ICredentialProvider
    IFACEMETHODIMP SetUsageScenario(CREDENTIAL_PROVIDER_USAGE_SCENARIO cpus, DWORD dwFlags) override;
    IFACEMETHODIMP SetSerialization(const CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* pcpcs) override;
    IFACEMETHODIMP Advise(ICredentialProviderEvents* pcpe, UINT_PTR upAdviseContext) override;
    IFACEMETHODIMP UnAdvise() override;
    IFACEMETHODIMP GetFieldDescriptorCount(DWORD* pdwCount) override;
    IFACEMETHODIMP GetFieldDescriptorAt(DWORD dwIndex, CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR** ppcpfd) override;
    IFACEMETHODIMP GetCredentialCount(DWORD* pdwCount, DWORD* pdwDefault, BOOL* pbAutoLogonWithDefault) override;
    IFACEMETHODIMP GetCredentialAt(DWORD dwIndex, ICredentialProviderCredential** ppcpc) override;

    void NotifyCredentialsChanged();
    CREDENTIAL_PROVIDER_USAGE_SCENARIO UsageScenario() const { return usageScenario_; }
    ULONG AuthPackage() const { return authPackage_; }
    bool AuthPackageValid() const { return authPackageValid_; }

private:
    ~BandKeyCredentialProvider();

    std::atomic<ULONG> refCount_ = 1;
    CREDENTIAL_PROVIDER_USAGE_SCENARIO usageScenario_ = CPUS_INVALID;
    ULONG authPackage_ = 0;
    bool authPackageValid_ = false;
    ICredentialProviderEvents* events_ = nullptr;
    UINT_PTR adviseContext_ = 0;
    BandKeyCredential* credential_ = nullptr;
};

// 单个凭据（登录 tile）
class BandKeyCredential : public ICredentialProviderCredential
{
public:
    BandKeyCredential(BandKeyCredentialProvider& provider);

    // IUnknown
    IFACEMETHODIMP QueryInterface(REFIID riid, void** ppv) override;
    IFACEMETHODIMP_(ULONG) AddRef() override;
    IFACEMETHODIMP_(ULONG) Release() override;

    // ICredentialProviderCredential
    IFACEMETHODIMP Advise(ICredentialProviderCredentialEvents* pcpce) override;
    IFACEMETHODIMP UnAdvise() override;
    IFACEMETHODIMP SetSelected(BOOL* pbAutoLogon) override;
    IFACEMETHODIMP SetDeselected() override;
    IFACEMETHODIMP GetFieldState(DWORD dwFieldID, CREDENTIAL_PROVIDER_FIELD_STATE* pcpfs, CREDENTIAL_PROVIDER_FIELD_INTERACTIVE_STATE* pcpfis) override;
    IFACEMETHODIMP GetStringValue(DWORD dwFieldID, PWSTR* ppwsz) override;
    IFACEMETHODIMP GetBitmapValue(DWORD dwFieldID, HBITMAP* phbmp) override;
    IFACEMETHODIMP GetCheckboxValue(DWORD dwFieldID, BOOL* pbChecked, PWSTR* ppwszLabel) override;
    IFACEMETHODIMP GetSubmitButtonValue(DWORD dwFieldID, DWORD* pdwAdjacentTo) override;
    IFACEMETHODIMP GetComboBoxValueCount(DWORD dwFieldID, DWORD* pcItems, DWORD* pdwSelectedItem) override;
    IFACEMETHODIMP GetComboBoxValueAt(DWORD dwFieldID, DWORD dwItem, PWSTR* ppwszItem) override;
    IFACEMETHODIMP SetStringValue(DWORD dwFieldID, PCWSTR pwz) override;
    IFACEMETHODIMP SetCheckboxValue(DWORD dwFieldID, BOOL bChecked) override;
    IFACEMETHODIMP SetComboBoxSelectedValue(DWORD dwFieldID, DWORD dwSelectedItem) override;
    IFACEMETHODIMP CommandLinkClicked(DWORD dwFieldID) override;
    IFACEMETHODIMP GetSerialization(CREDENTIAL_PROVIDER_GET_SERIALIZATION_RESPONSE* pcpgsr,
                                    CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* pcpcs,
                                    PWSTR* ppwszOptionalStatusText,
                                    CREDENTIAL_PROVIDER_STATUS_ICON* pcpsiOptionalStatusIcon) override;
    IFACEMETHODIMP ReportResult(NTSTATUS ntsStatus, NTSTATUS ntsSubstatus,
                                PWSTR* ppwszOptionalStatusText,
                                CREDENTIAL_PROVIDER_STATUS_ICON* pcpsiOptionalStatusIcon) override;

    void OnUnlockReady(bool ready);
    void OnAutoUnlock(long long requestId);
    void OnLegacyUnlock();
    bool AutoUnlockPending() const { return autoUnlockPending_.load(); }

private:
    ~BandKeyCredential();
    HRESULT BuildSerialization(CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION& cpcs);
    std::wstring StatusText() const;
    void RefreshFields();

    std::atomic<ULONG> refCount_ = 1;
    BandKeyCredentialProvider& provider_;
    ICredentialProviderCredentialEvents* events_ = nullptr;
    std::atomic<bool> authorized_ = false;
    std::atomic<bool> unlockReady_ = false;
    std::atomic<bool> autoUnlockPending_ = false;
    std::atomic<long long> autoUnlockRequestId_ = 0;
    std::mutex serializationMutex_;
    std::vector<BYTE> serializationBuffer_;
    PipeClient pipe_{*this};
};
