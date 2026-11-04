param(
    [ValidateSet('Record', 'Locate', 'Verify', 'SelfTest')][string]$Mode = 'Verify',
    [string]$Repository = $env:GITHUB_REPOSITORY,
    [string]$ReleaseSha = $env:GITHUB_SHA
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$requiredJobs = @('checks',
    'Remote update UI only (ephemeral API35 emulator)',
    'Optimized release runtime (disposable API35 emulator)', 'Validated PR source proof')
function Require($condition, $message) { if (!$condition) { throw $message } }
function IsSha($value) { $value -is [string] -and $value -cmatch '^[0-9a-f]{40}$' }
function IsId($value) { "$value" -cmatch '^[1-9][0-9]*$' }
function Keys($value, $expected) {
    Require ($null -ne $value -and !(Compare-Object -CaseSensitive $expected @($value.PSObject.Properties.Name))) 'Validation record fields differ from schema 1'
}
function NewContext($repo, $sha, $tree, $pr, $run, $jobs, $artifact) {
    Require ($repo -cin @('arisewanggithub/ScrcpySend', 'arisewanggithub/ScrcpyReceive') -and (IsSha $sha) -and (IsSha $tree)) 'Unsupported repository or release revision'
    Require ($pr.merged_at -and $pr.merge_commit_sha -ceq $sha -and $pr.base.ref -ceq 'main' -and $pr.base.repo.full_name -ceq $repo -and (IsId $pr.number) -and (IsSha $pr.head.sha)) 'Release must be the exact merge commit of a merged main PR'
    Require ($run.repository.full_name -ceq $repo -and $run.event -ceq 'pull_request' -and $run.path -ceq '.github/workflows/build-release.yml' -and $run.head_sha -ceq $pr.head.sha -and $run.status -ceq 'completed' -and $run.conclusion -ceq 'success' -and (IsId $run.id) -and (IsId $run.run_attempt)) 'Matching PR validation workflow did not complete successfully'
    foreach ($name in $requiredJobs) {
        $matching = @($jobs | Where-Object { $_.name -ceq $name })
        Require ($matching.Count -eq 1 -and $matching[0].status -ceq 'completed' -and $matching[0].conclusion -ceq 'success') "Required PR job did not execute successfully: $name"
    }
    Require (@($jobs | Where-Object { $_.status -cne 'completed' -or $_.conclusion -cnotin @('success', 'skipped') }).Count -eq 0) 'PR validation has unfinished or failed jobs'
    $name = "$($repo.Split('/')[1])-pr-validation-$($run.id)-$($run.run_attempt)"
    Require ($null -ne $artifact -and $artifact.name -ceq $name -and !$artifact.expired -and (IsId $artifact.id) -and $artifact.size_in_bytes -gt 0 -and $artifact.size_in_bytes -le 65536 -and "$($artifact.workflow_run.id)" -ceq "$($run.id)" -and $artifact.workflow_run.head_sha -ceq $pr.head.sha) 'Retained validation artifact is missing, expired or belongs to another revision'
    [pscustomobject][ordered]@{
        schema = 1; repository = $repo; releaseSha = $sha; treeSha = $tree
        pullRequest = [int]$pr.number; headSha = $pr.head.sha; runId = "$($run.id)"
        runAttempt = [int]$run.run_attempt; artifactId = "$($artifact.id)"; artifactName = $name
    }
}
function ConfirmProof($proof, $context) {
    Keys $context @('schema','repository','releaseSha','treeSha','pullRequest','headSha','runId','runAttempt','artifactId','artifactName')
    Keys $proof @('schema','repository','pullRequest','headSha','baseSha','testedSha','treeSha','runId','runAttempt')
    Require ($context.schema -eq 1 -and $proof.schema -eq 1) 'Unsupported validation schema'
    Require ($proof.repository -ceq $context.repository -and $proof.pullRequest -eq $context.pullRequest -and $proof.headSha -ceq $context.headSha -and $proof.treeSha -ceq $context.treeSha -and "$($proof.runId)" -ceq "$($context.runId)" -and $proof.runAttempt -eq $context.runAttempt -and (IsSha $proof.baseSha) -and (IsSha $proof.testedSha)) 'PR proof does not match the exact released source tree and validated run'
}
function GitValue([string[]]$Arguments) {
    $value = @(& git -C $root @Arguments)
    Require ($LASTEXITCODE -eq 0 -and $value.Count -eq 1) 'Cannot read checked-out Git revision'
    $value[0].Trim()
}
function GetGithub($path) {
    Require ($env:GH_TOKEN) 'A read-only GitHub Actions token is required for validation lookup'
    Invoke-RestMethod -Uri "https://api.github.com/$path" -Headers @{
        Authorization = "Bearer $env:GH_TOKEN"; Accept = 'application/vnd.github+json'
        'User-Agent' = 'Scrcpy-validated-release'
    } -TimeoutSec 30
}
function GithubItems($path, $field = '') {
    $separator = if ($path.Contains('?')) { '&' } else { '?' }
    for ($page = 1; $page -le 10; $page++) {
        $response = GetGithub "$path${separator}per_page=100&page=$page"
        $items = if ($field) { @($response.$field) } else { @($response) }
        $items
        if ($items.Count -lt 100) { return }
    }
    throw 'Validation lookup exceeded its bounded pagination limit'
}
function WriteJson($path, $value) {
    New-Item -ItemType Directory -Force -Path (Split-Path $path -Parent) | Out-Null
    [IO.File]::WriteAllText($path, ($value | ConvertTo-Json -Depth 8) + "`n", [Text.UTF8Encoding]::new($false))
}
if ($Mode -eq 'SelfTest') {
    $cases = 0
    foreach ($repo in @('arisewanggithub/ScrcpySend','arisewanggithub/ScrcpyReceive')) {
        $sha = 'a' * 40; $head = 'b' * 40; $tree = 'c' * 40
        $pr = [pscustomobject]@{ number=7; merged_at='2026-01-01'; merge_commit_sha=$sha; base=@{ref='main';repo=@{full_name=$repo}}; head=@{sha=$head} }
        $run = [pscustomobject]@{id=123;run_attempt=1;repository=@{full_name=$repo};event='pull_request';path='.github/workflows/build-release.yml';head_sha=$head;status='completed';conclusion='success'}
        $jobs = @($requiredJobs | ForEach-Object { [pscustomobject]@{name=$_;status='completed';conclusion='success'} })
        $artifact = [pscustomobject]@{id=456;name="$($repo.Split('/')[1])-pr-validation-123-1";expired=$false;size_in_bytes=512;workflow_run=@{id=123;head_sha=$head}}
        $context = NewContext $repo $sha $tree $pr $run $jobs $artifact
        $proof = [pscustomobject]@{schema=1;repository=$repo;pullRequest=7;headSha=$head;baseSha=('d'*40);testedSha=('e'*40);treeSha=$tree;runId='123';runAttempt=1}
        ConfirmProof $proof $context; $cases++
        foreach ($bad in @('unmerged','wrong-merge','wrong-base','wrong-head','push-run','failed-run','missing-job','skipped-job','duplicate-job','missing-artifact','expired-artifact','wrong-artifact-run','wrong-tree','wrong-proof-run','extra-field')) {
            $p = $pr | ConvertTo-Json -Depth 8 | ConvertFrom-Json
            $r = $run | ConvertTo-Json -Depth 8 | ConvertFrom-Json
            $j = $jobs | ConvertTo-Json -Depth 8 | ConvertFrom-Json
            $a = $artifact | ConvertTo-Json -Depth 8 | ConvertFrom-Json
            $v = $proof | ConvertTo-Json -Depth 8 | ConvertFrom-Json
            switch ($bad) {
                'unmerged' { $p.merged_at=$null }
                'wrong-merge' { $p.merge_commit_sha='f'*40 }
                'wrong-base' { $p.base.ref='other' }
                'wrong-head' { $r.head_sha='f'*40 }
                'push-run' { $r.event='push' }
                'failed-run' { $r.conclusion='failure' }
                'missing-job' { $j=@($j | Select-Object -First ($requiredJobs.Count - 1)) }
                'skipped-job' { $j[0].conclusion='skipped' }
                'duplicate-job' { $j += $j[0] }
                'missing-artifact' { $a=$null }
                'expired-artifact' { $a.expired=$true }
                'wrong-artifact-run' { $a.workflow_run.id=999 }
                'wrong-tree' { $v.treeSha='f'*40 }
                'wrong-proof-run' { $v.runId='999' }
                'extra-field' { $v | Add-Member unexpected $true }
            }
            $rejected = $false
            try { $c = NewContext $repo $sha $tree $p $r $j $a; ConfirmProof $v $c }
            catch [Management.Automation.RuntimeException] { $rejected=$true }
            Require $rejected "Invalid validation fixture was accepted: $bad"
            $cases++
        }
    }
    Write-Output "PASS PR release validation proof: $cases acceptance/rejection fixtures"
    return
}
Require ($Repository -cin @('arisewanggithub/ScrcpySend','arisewanggithub/ScrcpyReceive')) 'Unsupported repository'
if ($Mode -eq 'Record') {
    Require ($env:GITHUB_EVENT_NAME -ceq 'pull_request' -and (IsId $env:GITHUB_RUN_ID) -and (IsId $env:GITHUB_RUN_ATTEMPT)) 'Proof recording is PR-only'
    $event = Get-Content -LiteralPath $env:GITHUB_EVENT_PATH -Raw | ConvertFrom-Json
    Require ($event.pull_request.base.ref -ceq 'main' -and $event.pull_request.base.repo.full_name -ceq $Repository -and (IsId $event.number) -and (IsSha $event.pull_request.head.sha) -and (IsSha $event.pull_request.base.sha)) 'Invalid PR event identity'
    $tested = GitValue @('rev-parse','HEAD')
    Require ($tested -ceq $env:GITHUB_SHA -and (IsSha $tested)) 'Proof checkout differs from the PR merge revision'
    WriteJson (Join-Path $root 'publication-output/pr-validation.json') ([ordered]@{
        schema=1;repository=$Repository;pullRequest=[int]$event.number
        headSha=$event.pull_request.head.sha;baseSha=$event.pull_request.base.sha
        testedSha=$tested;treeSha=(GitValue @('rev-parse','HEAD^{tree}'))
        runId=$env:GITHUB_RUN_ID;runAttempt=[int]$env:GITHUB_RUN_ATTEMPT
    })
    Write-Output "Recorded validated PR #$($event.number) source tree"
    return
}
Require ((IsSha $ReleaseSha) -and (GitValue @('rev-parse','HEAD')) -ceq $ReleaseSha) 'Release checkout differs from the requested revision'
$tree = GitValue @('rev-parse','HEAD^{tree}')
$contextPath = Join-Path $root 'publication-output/validation-context.json'
if ($Mode -eq 'Locate') {
    & git -C $root merge-base --is-ancestor $ReleaseSha origin/main
    Require ($LASTEXITCODE -eq 0) 'Release revision is outside main history'
    Require ((GetGithub "repos/$Repository/branches/main").protected) 'Release requires protected main'
    $prs = @(GithubItems "repos/$Repository/commits/$ReleaseSha/pulls" | Where-Object { $_.merged_at -and $_.merge_commit_sha -ceq $ReleaseSha -and $_.base.ref -ceq 'main' })
    Require ($prs.Count -eq 1) 'Release must identify exactly one merged main PR'
    $pr = $prs[0]
    Require ((IsSha $pr.head.sha) -and $pr.base.repo.full_name -ceq $Repository) 'Merged PR identity is invalid'
    $runs = @(GithubItems "repos/$Repository/actions/runs?event=pull_request&head_sha=$($pr.head.sha)&status=completed" 'workflow_runs' | Where-Object { $_.path -ceq '.github/workflows/build-release.yml' })
    Require ($runs.Count -gt 0) 'No completed PR validation run exists for this revision'
    $run = $runs[0]
    $jobs = @(GithubItems "repos/$Repository/actions/runs/$($run.id)/jobs?filter=latest" 'jobs')
    $name = "$($Repository.Split('/')[1])-pr-validation-$($run.id)-$($run.run_attempt)"
    $artifacts = @(GithubItems "repos/$Repository/actions/runs/$($run.id)/artifacts" 'artifacts' | Where-Object { $_.name -ceq $name })
    Require ($artifacts.Count -eq 1) 'PR validation proof is missing or expired; revalidate the PR before releasing'
    $context = NewContext $Repository $ReleaseSha $tree $pr $run $jobs $artifacts[0]
    WriteJson $contextPath $context
    if ($env:GITHUB_OUTPUT) {
        [IO.File]::AppendAllText($env:GITHUB_OUTPUT, "run_id=$($context.runId)`nartifact_id=$($context.artifactId)`n", [Text.UTF8Encoding]::new($false))
    }
    Write-Output "Located retained proof for merged PR #$($context.pullRequest), run $($context.runId)"
    return
}
$context = Get-Content -LiteralPath $contextPath -Raw | ConvertFrom-Json
Require ($context.repository -ceq $Repository -and $context.releaseSha -ceq $ReleaseSha -and $context.treeSha -ceq $tree) 'Downloaded proof context differs from the release checkout'
$proofPath = Join-Path $root 'publication-output/pr-validation/pr-validation.json'
Require ((Get-Item -LiteralPath $proofPath).Length -le 16384) 'PR proof exceeds its bounded JSON size'
ConfirmProof (Get-Content -LiteralPath $proofPath -Raw | ConvertFrom-Json) $context
Write-Output "PASS release revision $ReleaseSha matches validated PR #$($context.pullRequest), tree $tree"
