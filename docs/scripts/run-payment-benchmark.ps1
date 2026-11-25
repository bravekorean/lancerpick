param(
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Label = 'current',
    [ValidateRange(2,64)][int]$Requests = 24,
    [ValidateRange(1,10)][int]$Repeats = 3
)

$ErrorActionPreference = 'Stop'
foreach ($name in @('TEST_DB_URL','TEST_DB_USERNAME','TEST_DB_PASSWORD')) {
    if (-not [Environment]::GetEnvironmentVariable($name)) {
        throw "Set $name for an isolated lancerpick_test or lancerpick_ci database first."
    }
}
if ($env:TEST_DB_URL -notmatch '^jdbc:mysql://[a-zA-Z0-9.\-]+(?::[0-9]+)?/lancerpick_(test|ci)(\?[^#]*)?$') {
    throw 'Only lancerpick_test or lancerpick_ci databases are allowed.'
}
$backend = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../LancerPick'))
$previous = @{}
foreach ($name in @('PAYMENT_BENCHMARK','PAYMENT_BENCHMARK_LABEL','PAYMENT_BENCHMARK_REQUESTS','PAYMENT_BENCHMARK_REPEATS')) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name)
}
try {
    $env:PAYMENT_BENCHMARK = 'true'
    $env:PAYMENT_BENCHMARK_LABEL = $Label
    $env:PAYMENT_BENCHMARK_REQUESTS = "$Requests"
    $env:PAYMENT_BENCHMARK_REPEATS = "$Repeats"
    Push-Location $backend
    try {
        # Force execution: Gradle does not automatically track these environment variables as inputs.
        & ./gradlew.bat test --tests '*PaymentConcurrencyBenchmarkTests' --rerun-tasks --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Payment benchmark failed (exit $LASTEXITCODE)." }
        Write-Host "CSV: $(Join-Path $backend "build/reports/payment-benchmark/$Label.csv")"
    } finally { Pop-Location }
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
