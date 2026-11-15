# gitregen : relance la compilation GitHub Actions d'un dépôt et télécharge le résultat (APK, exe, etc.)
# Usage (coller dans la console PowerShell, puis) :  gitregen
#   gitregen -Repo utilisateur/autre-depot -Workflow build.yml -Ref main -OutDir C:\Sortie
# Prérequis : GitHub CLI (gh) installé et connecté (gh auth login).
function gitregen {
    param(
        [string]$Repo = 'gregorybrice-coder/Mini-launcher',
        [string]$Workflow = 'build.yml',
        [string]$Ref = 'main',
        [string]$OutDir = (Get-Location).Path   # par défaut : le dossier courant, pas %TEMP%
    )
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        Write-Host "GitHub CLI (gh) introuvable : installez-le puis lancez 'gh auth login'."
        return
    }
    $avant = gh run list --repo $Repo --workflow $Workflow --limit 1 --json databaseId -q '.[0].databaseId'
    gh workflow run $Workflow --repo $Repo --ref $Ref
    if ($LASTEXITCODE) { return }
    $id = $avant
    for ($i = 0; $i -lt 30 -and $id -eq $avant; $i++) {
        Start-Sleep 3
        $id = gh run list --repo $Repo --workflow $Workflow --limit 1 --json databaseId -q '.[0].databaseId'
    }
    gh run watch $id --repo $Repo --exit-status
    if ($LASTEXITCODE) { Write-Host "La compilation a échoué (run $id)."; return }
    gh run download $id --repo $Repo --dir $OutDir
    Write-Host "Terminé : fichiers dans $OutDir"
}
