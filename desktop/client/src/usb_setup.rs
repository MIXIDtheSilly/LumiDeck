//! Sets Windows up, once, for the USB link (usb.rs): `lumideck --setup-usb`, as an
//! administrator.
//!
//! Programs reach a USB device's interface through WinUSB, which Windows only puts on
//! interfaces that ask for it. ADB's does; the accessory's doesn't, so Windows leaves it
//! without a driver or takes it for something else (MTP, with Samsung's drivers). This
//! puts Windows' own drivers on the device as it is in accessory mode: the composite
//! driver on the device, which splits it into its interfaces, and WinUSB on the accessory's.
//! Both come with Windows, so there's nothing to download or sign. Windows remembers it
//! for that device.

use std::thread;
use std::time::{Duration, Instant};

use anyhow::{Context, Result, anyhow, bail};
use windows::Win32::Devices::DeviceAndDriverInstallation::{
    CM_Get_Child, CM_Get_Device_IDW, CM_Get_Sibling, CM_LOCATE_DEVNODE_NORMAL, CM_Locate_DevNodeW, CR_SUCCESS,
    DI_ENUMSINGLEINF, DI_FLAGSEX_ALLOWEXCLUDEDDRVS, DICS_FLAG_GLOBAL, DIINSTALLDEVICE_FLAGS, DIREG_DEV,
    DiInstallDevice, HDEVINFO, SETUP_DI_DRIVER_TYPE, SP_DEVINFO_DATA, SP_DEVINSTALL_PARAMS_W, SP_DRVINFO_DATA_V2_W,
    SPDIT_CLASSDRIVER, SPDIT_COMPATDRIVER, SPDRP_CLASSGUID, SPDRP_SERVICE, SetupDiBuildDriverInfoList,
    SetupDiCreateDevRegKeyW, SetupDiCreateDeviceInfoList, SetupDiDestroyDeviceInfoList, SetupDiEnumDriverInfoW,
    SetupDiGetDeviceInstallParamsW, SetupDiGetDeviceRegistryPropertyW, SetupDiGetINFClassW, SetupDiOpenDeviceInfoW,
    SetupDiSetDeviceInstallParamsW, SetupDiSetDeviceRegistryPropertyW, SetupDiSetSelectedDriverW,
};
use windows::Win32::System::Registry::{
    HKEY, HKEY_LOCAL_MACHINE, KEY_READ, REG_MULTI_SZ, RRF_RT_REG_SZ, RegCloseKey, RegEnumKeyExW, RegGetValueW,
    RegOpenKeyExW, RegSetValueExW,
};
use windows::core::{GUID, HSTRING, PCWSTR, PWSTR};

use crate::adb::Adb;
use crate::usb::{ACCESSORY, GOOGLE, INTERFACE};

/// Where programs find the accessory's interface. LumiDeck's own.
const INTERFACE_GUID: &str = "{6A1D5E3C-2B7F-4C1A-9E57-4C756D694465}";

/// Whether this PC is set up already, for any device.
pub fn done() -> bool {
    ACCESSORY.iter().any(|&product| {
        // The accessory with ADB is a composite device; the accessory alone isn't.
        let interface = if product == ACCESSORY[1] { format!("&MI_{INTERFACE:02X}") } else { String::new() };
        let key = format!("SYSTEM\\CurrentControlSet\\Enum\\USB\\VID_{GOOGLE:04X}&PID_{product:04X}{interface}");
        subkeys(&key).iter().any(|instance| {
            value(&format!("{key}\\{instance}"), "Service").is_some_and(|s| s.eq_ignore_ascii_case("winusb"))
        })
    })
}

pub fn run(adb: &Adb) -> Result<()> {
    crate::usb::switch(adb)?;
    let device = nusb::list_devices()?
        .find(|d| d.serial_number() == Some(adb.serial.as_str()) && ACCESSORY.contains(&d.product_id()))
        .context("The device's USB isn't switched over")?;
    let id = device.instance_id().to_string_lossy().into_owned();
    let accessory = if device.product_id() == ACCESSORY[1] {
        if !driver(&id)?.eq_ignore_ascii_case("usbccgp") {
            println!("Putting Windows' composite driver on the device");
            install(&id, "usb.inf", SPDIT_COMPATDRIVER, |name| name.contains("Composite"))?;
        }
        wait_for_interface(&id)?
    } else {
        id
    };
    println!("Putting WinUSB on LumiDeck's interface");
    set_interface_guid(&accessory)?;
    install(&accessory, "winusb.inf", SPDIT_CLASSDRIVER, |name| name.contains("WinUsb"))?;
    if !done() {
        bail!("WinUSB didn't take");
    }
    println!("Done: LumiDeck connects straight over USB from now on.");
    Ok(())
}

/// The accessory's interface on the composite device `parent`, once Windows has it.
fn wait_for_interface(parent: &str) -> Result<String> {
    let deadline = Instant::now() + Duration::from_secs(15);
    let wanted = format!("&MI_{INTERFACE:02X}");
    loop {
        if let Some(child) = children(parent)?.into_iter().find(|c| c.to_uppercase().contains(&wanted)) {
            return Ok(child);
        }
        if Instant::now() > deadline {
            bail!("Windows didn't split the device into its interfaces");
        }
        thread::sleep(Duration::from_millis(250));
    }
}

fn children(parent: &str) -> Result<Vec<String>> {
    let mut node = 0;
    let mut out = Vec::new();
    unsafe {
        if CM_Locate_DevNodeW(&mut node, &HSTRING::from(parent), CM_LOCATE_DEVNODE_NORMAL) != CR_SUCCESS {
            bail!("Windows lost the device");
        }
        let mut child = 0;
        let mut found = CM_Get_Child(&mut child, node, 0) == CR_SUCCESS;
        while found {
            let mut id = [0u16; 512];
            if CM_Get_Device_IDW(child, &mut id, 0) == CR_SUCCESS {
                out.push(wide(&id));
            }
            let mut next = 0;
            found = CM_Get_Sibling(&mut next, child, 0) == CR_SUCCESS;
            child = next;
        }
    }
    Ok(out)
}

/// One device, opened for SetupAPI.
struct Device {
    set: HDEVINFO,
    data: SP_DEVINFO_DATA,
}

impl Device {
    fn open(id: &str) -> Result<Device> {
        unsafe {
            let set = SetupDiCreateDeviceInfoList(None, None)?;
            let mut data = SP_DEVINFO_DATA { cbSize: size_of::<SP_DEVINFO_DATA>() as u32, ..Default::default() };
            if let Err(e) = SetupDiOpenDeviceInfoW(set, &HSTRING::from(id), None, 0, Some(&mut data)) {
                let _ = SetupDiDestroyDeviceInfoList(set);
                return Err(e.into());
            }
            Ok(Device { set, data })
        }
    }
}

impl Drop for Device {
    fn drop(&mut self) {
        let _ = unsafe { SetupDiDestroyDeviceInfoList(self.set) };
    }
}

/// The service (driver) Windows runs for the device, or "" for none.
fn driver(id: &str) -> Result<String> {
    let device = Device::open(id)?;
    let mut buffer = [0u8; 512];
    let found = unsafe {
        SetupDiGetDeviceRegistryPropertyW(device.set, &device.data, SPDRP_SERVICE, None, Some(&mut buffer), None)
    };
    if found.is_err() {
        return Ok(String::new());
    }
    let units: Vec<u16> = buffer.as_chunks::<2>().0.iter().map(|&b| u16::from_le_bytes(b)).collect();
    Ok(wide(&units))
}

/// Installs the driver from Windows' `inf` (in C:\Windows\INF) whose name `pick`s on the
/// device, as Device Manager does when one picks a driver from a list. `kind` is
/// SPDIT_COMPATDRIVER for a driver made for devices like it, or SPDIT_CLASSDRIVER for any
/// in the INF, which WinUSB's generic one needs.
fn install(id: &str, inf: &str, kind: SETUP_DI_DRIVER_TYPE, pick: impl Fn(&str) -> bool) -> Result<()> {
    let windows = std::env::var("SystemRoot").unwrap_or_else(|_| "C:\\Windows".into());
    let path = format!("{windows}\\INF\\{inf}");
    let mut device = Device::open(id)?;
    unsafe {
        if kind == SPDIT_CLASSDRIVER {
            // The device takes the INF's class, as the list is of the device's class.
            let mut class = GUID::zeroed();
            let mut name = [0u16; 64];
            SetupDiGetINFClassW(&HSTRING::from(path.as_str()), &mut class, &mut name, None)?;
            let text: Vec<u8> = format!("{{{class:?}}}\0").encode_utf16().flat_map(u16::to_le_bytes).collect();
            SetupDiSetDeviceRegistryPropertyW(device.set, &mut device.data, SPDRP_CLASSGUID, Some(&text))?;
            device = Device::open(id)?;
        }
        let mut params = SP_DEVINSTALL_PARAMS_W { cbSize: size_of::<SP_DEVINSTALL_PARAMS_W>() as u32, ..Default::default() };
        SetupDiGetDeviceInstallParamsW(device.set, Some(&device.data), &mut params)?;
        params.Flags |= DI_ENUMSINGLEINF;
        params.FlagsEx |= DI_FLAGSEX_ALLOWEXCLUDEDDRVS;
        let units: Vec<u16> = path.encode_utf16().collect();
        params.DriverPath[..units.len()].copy_from_slice(&units);
        SetupDiSetDeviceInstallParamsW(device.set, Some(&device.data), &params)?;
        SetupDiBuildDriverInfoList(device.set, Some(&mut device.data), kind)?;
        let mut index = 0;
        let mut names = Vec::new();
        let mut chosen = loop {
            let mut info =
                SP_DRVINFO_DATA_V2_W { cbSize: size_of::<SP_DRVINFO_DATA_V2_W>() as u32, ..Default::default() };
            if SetupDiEnumDriverInfoW(device.set, Some(&device.data), kind, index, &mut info).is_err() {
                bail!("{inf} has no driver for the device (it has: {})", names.join(", "));
            }
            let name = wide(&info.Description);
            if pick(&name) {
                break info;
            }
            names.push(name);
            index += 1;
        };
        SetupDiSetSelectedDriverW(device.set, Some(&mut device.data), Some(&mut chosen))?;
        DiInstallDevice(None, device.set, &device.data, Some(&chosen), DIINSTALLDEVICE_FLAGS(0), None)
            .map_err(|e| anyhow!("Windows didn't install {inf} ({e}). Is this running as an administrator?"))?;
    }
    Ok(())
}

/// Tells WinUSB which interface class to put the accessory's interface in, where usb.rs
/// (through nusb) looks for it.
fn set_interface_guid(id: &str) -> Result<()> {
    let device = Device::open(id)?;
    unsafe {
        let key = SetupDiCreateDevRegKeyW(device.set, &device.data, DICS_FLAG_GLOBAL.0, 0, DIREG_DEV, None, PCWSTR::null())?;
        let value: Vec<u8> = format!("{INTERFACE_GUID}\0\0").encode_utf16().flat_map(u16::to_le_bytes).collect();
        let result = RegSetValueExW(key, &HSTRING::from("DeviceInterfaceGUIDs"), None, REG_MULTI_SZ, Some(&value));
        let _ = RegCloseKey(key);
        result.ok().context("Couldn't write the interface's settings")?;
    }
    Ok(())
}

fn subkeys(path: &str) -> Vec<String> {
    let mut out = Vec::new();
    unsafe {
        let mut key = HKEY::default();
        if RegOpenKeyExW(HKEY_LOCAL_MACHINE, &HSTRING::from(path), None, KEY_READ, &mut key).is_err() {
            return out;
        }
        let mut index = 0;
        loop {
            let mut name = [0u16; 256];
            let mut len = name.len() as u32;
            if RegEnumKeyExW(key, index, Some(PWSTR(name.as_mut_ptr())), &mut len, None, None, None, None).is_err() {
                break;
            }
            out.push(String::from_utf16_lossy(&name[..len as usize]));
            index += 1;
        }
        let _ = RegCloseKey(key);
    }
    out
}

fn value(path: &str, name: &str) -> Option<String> {
    let mut data = [0u16; 256];
    let mut size = (data.len() * 2) as u32;
    unsafe {
        RegGetValueW(
            HKEY_LOCAL_MACHINE,
            &HSTRING::from(path),
            &HSTRING::from(name),
            RRF_RT_REG_SZ,
            None,
            Some(data.as_mut_ptr().cast()),
            Some(&mut size),
        )
        .ok()
        .ok()?;
    }
    Some(wide(&data))
}

/// A null-terminated UTF-16 string.
fn wide(units: &[u16]) -> String {
    let end = units.iter().position(|&u| u == 0).unwrap_or(units.len());
    String::from_utf16_lossy(&units[..end])
}
