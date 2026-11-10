#include <new>
#include <atomic>

#include "BandKeyCredentialProvider.h"

HINSTANCE g_hInst = nullptr;

class BandKeyProviderClassFactory final : public IClassFactory
{
public:
    IFACEMETHODIMP QueryInterface(REFIID riid, void** ppv) override
    {
        if (riid == __uuidof(IUnknown) || riid == __uuidof(IClassFactory))
        {
            *ppv = static_cast<IClassFactory*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }

    IFACEMETHODIMP_(ULONG) AddRef() override { return ++ref_; }

    IFACEMETHODIMP_(ULONG) Release() override
    {
        const ULONG r = --ref_;
        if (r == 0)
        {
            delete this;
        }
        return r;
    }

    IFACEMETHODIMP CreateInstance(IUnknown* pUnkOuter, REFIID riid, void** ppv) override
    {
        if (pUnkOuter)
        {
            return CLASS_E_NOAGGREGATION;
        }

        auto* provider = new (std::nothrow) BandKeyCredentialProvider();
        if (!provider)
        {
            return E_OUTOFMEMORY;
        }

        const HRESULT hr = provider->QueryInterface(riid, ppv);
        provider->Release();
        return hr;
    }

    IFACEMETHODIMP LockServer(BOOL) override { return S_OK; }

private:
    std::atomic<ULONG> ref_ = 1;
};

STDAPI DllCanUnloadNow()
{
    return S_OK;
}

STDAPI DllGetClassObject(REFCLSID rclsid, REFIID riid, void** ppv)
{
    if (!IsEqualCLSID(rclsid, CLSID_BandKeyCredentialProvider))
    {
        return CLASS_E_CLASSNOTAVAILABLE;
    }

    auto* factory = new (std::nothrow) BandKeyProviderClassFactory();
    if (!factory)
    {
        return E_OUTOFMEMORY;
    }

    const HRESULT hr = factory->QueryInterface(riid, ppv);
    factory->Release();
    return hr;
}

static HRESULT WriteRegValue(HKEY root, PCWSTR subkey, PCWSTR valueName, PCWSTR value)
{
    HKEY key = nullptr;
    HRESULT hr = HRESULT_FROM_WIN32(RegCreateKeyExW(root, subkey, 0, nullptr, 0, KEY_WRITE, nullptr, &key, nullptr));
    if (SUCCEEDED(hr))
    {
        const DWORD size = static_cast<DWORD>((wcslen(value) + 1) * sizeof(wchar_t));
        hr = HRESULT_FROM_WIN32(RegSetValueExW(key, valueName, 0, REG_SZ,
                                                reinterpret_cast<const BYTE*>(value), size));
        RegCloseKey(key);
    }
    return hr;
}

STDAPI DllRegisterServer()
{
    wchar_t modulePath[MAX_PATH] = {};
    GetModuleFileNameW(g_hInst, modulePath, MAX_PATH);

    constexpr PCWSTR clsid = L"CLSID\\{E86A4C6E-5B8A-4C2D-9F3A-1B2C3D4E5F60}";
    constexpr PCWSTR providerKey =
        L"SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Authentication\\Credential Providers"
        L"\\{E86A4C6E-5B8A-4C2D-9F3A-1B2C3D4E5F60}";

    WriteRegValue(HKEY_CLASSES_ROOT, clsid, nullptr, L"BandKeyCredentialProvider");
    WriteRegValue(HKEY_CLASSES_ROOT, (std::wstring(clsid) + L"\\InprocServer32").c_str(), nullptr, modulePath);
    WriteRegValue(HKEY_CLASSES_ROOT, (std::wstring(clsid) + L"\\InprocServer32").c_str(), L"ThreadingModel", L"Apartment");
    WriteRegValue(HKEY_LOCAL_MACHINE, providerKey, nullptr, L"BandKeyCredentialProvider");
    return S_OK;
}

STDAPI DllUnregisterServer()
{
    constexpr PCWSTR clsid = L"CLSID\\{E86A4C6E-5B8A-4C2D-9F3A-1B2C3D4E5F60}";
    RegDeleteTreeW(HKEY_CLASSES_ROOT, clsid);
    RegDeleteTreeW(HKEY_LOCAL_MACHINE,
        L"SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Authentication\\Credential Providers"
        L"\\{E86A4C6E-5B8A-4C2D-9F3A-1B2C3D4E5F60}");
    return S_OK;
}

BOOL WINAPI DllMain(HINSTANCE hinstDll, DWORD reason, LPVOID)
{
    if (reason == DLL_PROCESS_ATTACH)
    {
        g_hInst = hinstDll;
        DisableThreadLibraryCalls(hinstDll);
    }
    return TRUE;
}
