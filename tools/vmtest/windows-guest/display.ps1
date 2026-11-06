# Sets the VM's screen size, kept across restarts: display.ps1 <width> <height>. With no size,
# lists the sizes the display driver offers. Runs through runner.ps1, in the desktop session.
param([int]$Width, [int]$Height)
if (-not ('Display' -as [type])) {
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

public static class Display {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct DEVMODE {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmDeviceName;
        public short dmSpecVersion, dmDriverVersion, dmSize, dmDriverExtra;
        public int dmFields, dmPositionX, dmPositionY, dmDisplayOrientation, dmDisplayFixedOutput;
        public short dmColor, dmDuplex, dmYResolution, dmTTOption, dmCollate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmFormName;
        public short dmLogPixels;
        public int dmBitsPerPel, dmPelsWidth, dmPelsHeight, dmDisplayFlags, dmDisplayFrequency;
        public int dmICMMethod, dmICMIntent, dmMediaType, dmDitherType, dmReserved1, dmReserved2;
        public int dmPanningWidth, dmPanningHeight;
    }
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern bool EnumDisplaySettings(string device, int mode, ref DEVMODE dm);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int ChangeDisplaySettings(ref DEVMODE dm, int flags);

    static DEVMODE Blank() { var dm = new DEVMODE(); dm.dmSize = (short)Marshal.SizeOf(typeof(DEVMODE)); return dm; }

    public static List<string> Modes() {
        var found = new List<string>();
        var dm = Blank();
        for (int i = 0; EnumDisplaySettings(null, i, ref dm); i++) {
            string size = dm.dmPelsWidth + "x" + dm.dmPelsHeight;
            if (!found.Contains(size)) found.Add(size);
        }
        return found;
    }

    // 0 is DISP_CHANGE_SUCCESSFUL; CDS_UPDATEREGISTRY (1) keeps the size for the next logon.
    public static int Set(int width, int height) {
        var dm = Blank();
        EnumDisplaySettings(null, -1, ref dm);
        dm.dmPelsWidth = width;
        dm.dmPelsHeight = height;
        dm.dmFields = 0x00080000 | 0x00100000;
        return ChangeDisplaySettings(ref dm, 1);
    }
}
'@
}
if ($Width) { "result $([Display]::Set($Width, $Height))" } else { [Display]::Modes() -join ' ' }
