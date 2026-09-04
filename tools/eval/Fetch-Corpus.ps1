# Downloads the public evaluation corpus for the search-eval harness.
# All documents are public, stable-URL PDFs; nothing personal is involved.
# Run on the dev machine, then push to the device (see README.md).

$ErrorActionPreference = "Stop"
$out = Join-Path $PSScriptRoot "corpus"
New-Item -ItemType Directory -Force $out | Out-Null
$ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/128.0 Safari/537.36"

$docs = @(
    @{ Name = "i765-instructions.pdf"; Url = "https://www.uscis.gov/sites/default/files/document/forms/i-765instr.pdf" },
    @{ Name = "i9-form.pdf";           Url = "https://www.uscis.gov/sites/default/files/document/forms/i-9.pdf" },
    @{ Name = "f1040.pdf";             Url = "https://www.irs.gov/pub/irs-pdf/f1040.pdf" },
    @{ Name = "f8843.pdf";             Url = "https://www.irs.gov/pub/irs-pdf/f8843.pdf" },
    @{ Name = "fw4.pdf";               Url = "https://www.irs.gov/pub/irs-pdf/fw4.pdf" },
    @{ Name = "attention-paper.pdf";   Url = "https://arxiv.org/pdf/1706.03762" },
    @{ Name = "bitcoin-whitepaper.pdf";Url = "https://bitcoin.org/bitcoin.pdf" },
    @{ Name = "nist-privacy.pdf";      Url = "https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-63-3.pdf" },
    @{ Name = "rfc9110-http.pdf";      Url = "https://www.rfc-editor.org/rfc/rfc9110.pdf" }
)

foreach ($d in $docs) {
    $path = Join-Path $out $d.Name
    Write-Host "Fetching $($d.Name)..."
    Invoke-WebRequest -Uri $d.Url -OutFile $path -UserAgent $ua
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $header = [System.Text.Encoding]::ASCII.GetString($bytes[0..4])
    if ($header -ne "%PDF-") {
        Write-Warning "$($d.Name) is not a PDF (got '$header') - the source may have moved or blocked the request."
    }
}
Write-Host "Done. Corpus in $out"
