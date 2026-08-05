开发环境需要手动导入.env环境变量，临时导入PowerShell：

Get-Content .env | ForEach-Object {
$line = $_.Trim()

    if ($line -and -not $line.StartsWith("#")) {
        $name, $value = $line -split "=", 2

        $name = $name.Trim()
        $value = $value.Trim().Trim('"').Trim("'")

        Set-Item -Path "Env:$name" -Value $value
    }
}
验证方式：
$env:AI_BAILIAN_API_KEY
$env:PROVIDER_DEEPSEEK_API_KEY

如果没有输出代表当前终端无法读取变量；成功时会在终端输出指定变量值