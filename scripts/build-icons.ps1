$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Add-Type -AssemblyName System.Drawing
$source = [Drawing.Image]::FromFile((Join-Path $root 'artwork/launcher-source.png'))
if ($source.Width -ne $source.Height) { $source.Dispose(); throw 'Icon source must be square; cropping is not performed' }
function Save-Icon([string]$Path, [int]$Size) {
    $bitmap = New-Object Drawing.Bitmap $Size, $Size
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.CompositingQuality = [Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $radius = [single]($Size * 0.08)
        $diameter = [single]($radius * 2)
        $clip = New-Object Drawing.Drawing2D.GraphicsPath
        try {
            $clip.AddArc(0, 0, $diameter, $diameter, 180, 90)
            $clip.AddArc(($Size-$diameter), 0, $diameter, $diameter, 270, 90)
            $clip.AddArc(($Size-$diameter), ($Size-$diameter), $diameter, $diameter, 0, 90)
            $clip.AddArc(0, ($Size-$diameter), $diameter, $diameter, 90, 90)
            $clip.CloseFigure()
            $graphics.SetClip($clip)
        } finally { $clip.Dispose() }
        $attributes = New-Object Drawing.Imaging.ImageAttributes
        try {
            # Mirror at the source edge so bicubic scaling does not introduce transparent seams.
            $attributes.SetWrapMode([Drawing.Drawing2D.WrapMode]::TileFlipXY)
            $target = New-Object Drawing.Rectangle 0, 0, $Size, $Size
            $graphics.DrawImage($source, $target, 0, 0, $source.Width, $source.Height,
                    [Drawing.GraphicsUnit]::Pixel, $attributes)
        } finally { $attributes.Dispose() }
        $bitmap.Save($Path, [Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
}
try {
    Save-Icon (Join-Path $root 'app/src/main/res/drawable-nodpi/brand_portrait.png') 512
    Write-Output 'ICON_ASSETS_OK source=full_square size=512 corner_radius=8_percent crop=none redraw=none'
} finally { $source.Dispose() }
