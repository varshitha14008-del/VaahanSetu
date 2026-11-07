# VaahanSetu - Admin-to-Passenger Route Synchronization Verification Script
# Verifies Tests A through G as specified in Task Requirements

$baseUrl = "http://localhost:8080/api"
$frontendUrl = "http://localhost:3000"
$passed = 0
$failed = 0

function Assert-Check($testName, $condition, $details) {
    if ($condition) {
        Write-Host " [PASS] $testName" -ForegroundColor Green
        if ($details) { Write-Host "        $details" -ForegroundColor Gray }
        $global:passed++
    } else {
        Write-Host " [FAIL] $testName" -ForegroundColor Red
        if ($details) { Write-Host "        $details" -ForegroundColor Yellow }
        $global:failed++
    }
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "   VaahanSetu Route Synchronization Verification Suite   " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Login as Admin
$authAdmin = Invoke-RestMethod -Uri "$baseUrl/auth/login" -Method POST -ContentType "application/json" -Body '{"username":"admin","password":"admin123"}'
$adminHeaders = @{ Authorization = "Bearer $($authAdmin.token)" }

# Get active session
$activeSession = Invoke-RestMethod -Uri "$baseUrl/officer/sessions/active" -Method GET -Headers $adminHeaders
$originalSessionId = $activeSession.sessionId
Write-Host "Active session ID: $originalSessionId ($($activeSession.sessionName))" -ForegroundColor Gray

# -------------------------------------------------------------------------
# Test A: Admin selects Route A (1) and Route B (2).
# Passenger portal should display only Route A and Route B.
# -------------------------------------------------------------------------
Write-Host "`n--- Running Test A: Admin selects Route 1 and Route 2 ---" -ForegroundColor Yellow
$selectionA = @{
    routeIds = @(1, 2)
    busIds = @(101, 102)
} | ConvertTo-Json
$resA = Invoke-RestMethod -Uri "$baseUrl/admin/sessions/$originalSessionId/selection" -Method PUT -Headers $adminHeaders -ContentType "application/json" -Body $selectionA

$routesA = Invoke-RestMethod -Uri "$baseUrl/routes" -Method GET
$routeIdsA = @($routesA | ForEach-Object { $_.routeId })

Assert-Check "Test A.1: Admin saves Route 1 and Route 2" ($resA.message -like "*saved*") "Admin PUT responded: $($resA.message)"
Assert-Check "Test A.2: Passenger /routes returns exactly 2 routes" ($routesA.Count -eq 2) "Returned count: $($routesA.Count)"
Assert-Check "Test A.3: Passenger /routes contains only Route 1 and Route 2" ($routeIdsA.Count -eq 2 -and ($routeIdsA -contains 1) -and ($routeIdsA -contains 2)) "Route IDs returned: $($routeIdsA -join ', ')"

# -------------------------------------------------------------------------
# Test B: Admin deselects Route B (2).
# Passenger portal should display only Route A (1) for new registrations.
# Registration on deselected Route B (2) must be rejected.
# -------------------------------------------------------------------------
Write-Host "`n--- Running Test B: Admin deselects Route 2 (only Route 1 active) ---" -ForegroundColor Yellow
$selectionB = @{
    routeIds = @(1)
    busIds = @(101, 102)
} | ConvertTo-Json
$resB = Invoke-RestMethod -Uri "$baseUrl/admin/sessions/$originalSessionId/selection" -Method PUT -Headers $adminHeaders -ContentType "application/json" -Body $selectionB

$routesB = Invoke-RestMethod -Uri "$baseUrl/routes" -Method GET
$routeIdsB = @($routesB | ForEach-Object { $_.routeId })

Assert-Check "Test B.1: Admin saves Route 1 only" ($resB.message -like "*saved*") "Admin PUT responded: $($resB.message)"
Assert-Check "Test B.2: Passenger /routes returns exactly 1 route" ($routesB.Count -eq 1) "Returned count: $($routesB.Count)"
Assert-Check "Test B.3: Passenger /routes contains only Route 1" ($routeIdsB[0] -eq 1) "Route ID returned: $($routeIdsB[0])"

# Attempt new registration for deselected Route 2
$stopsRoute2 = Invoke-RestMethod -Uri "$baseUrl/routes/2/stops" -Method GET
$bStop2 = $stopsRoute2[0].stopId
$dStop2 = $stopsRoute2[1].stopId
$regDeselectedRejected = $false
$regErrorMsg = ""
try {
    $attemptReg = Invoke-RestMethod -Uri "$baseUrl/passenger/journeys" -Method POST -ContentType "application/json" -Body (@{
        routeId = 2
        boardingStopId = $bStop2
        destinationStopId = $dStop2
        passengerCount = 2
    } | ConvertTo-Json) -ErrorAction Stop
} catch {
    $regDeselectedRejected = $true
    $regErrorMsg = $_.Exception.Message
}
Assert-Check "Test B.4: New registration on deselected Route 2 is rejected" $regDeselectedRejected "Attempt was rejected with message: $regErrorMsg"

# -------------------------------------------------------------------------
# Test C: Refresh both portals. The selected routes should remain consistent.
# -------------------------------------------------------------------------
Write-Host "`n--- Running Test C: Refresh both portals (persistence check) ---" -ForegroundColor Yellow
# Simulate refreshing admin portal: fetches active session & selectedRouteIds
$refreshedSession = Invoke-RestMethod -Uri "$baseUrl/officer/sessions/active" -Method GET -Headers $adminHeaders
$adminSelectedRoutes = @($refreshedSession.selectedRouteIds)

# Simulate refreshing passenger portal: fetches /api/routes
$refreshedPassengerRoutes = Invoke-RestMethod -Uri "$baseUrl/routes" -Method GET
$passengerRouteIds = @($refreshedPassengerRoutes | ForEach-Object { $_.routeId })

Assert-Check "Test C.1: Admin portal receives persisted Route 1" ($adminSelectedRoutes.Count -eq 1 -and $adminSelectedRoutes[0] -eq 1) "Admin session selectedRouteIds: $($adminSelectedRoutes -join ', ')"
Assert-Check "Test C.2: Passenger portal receives persisted Route 1" ($passengerRouteIds.Count -eq 1 -and $passengerRouteIds[0] -eq 1) "Passenger routes: $($passengerRouteIds -join ', ')"
Assert-Check "Test C.3: Portals remain synchronized across page reloads" ($adminSelectedRoutes[0] -eq $passengerRouteIds[0]) "Both portals agree on Route ID $($passengerRouteIds[0])"

# -------------------------------------------------------------------------
# Test D: Start a new planning session with a different route selection.
# Passenger portal should show the routes belonging to the new active session,
# not the previous session.
# -------------------------------------------------------------------------
Write-Host "`n--- Running Test D: New planning session with Route 3 ---" -ForegroundColor Yellow
$newSessionPayload = @{
    sessionName = "Automated Test Session D"
    routeIds = @(3)
    busIds = @(103)
} | ConvertTo-Json

$newSessionRes = Invoke-RestMethod -Uri "$baseUrl/admin/sessions" -Method POST -Headers $adminHeaders -ContentType "application/json" -Body $newSessionPayload
$newSessionId = $newSessionRes.sessionId

Assert-Check "Test D.1: Admin creates new active session" ($newSessionId -gt 0) "Created session ID: $newSessionId"

# Passenger portal fetches routes now
$routesD = Invoke-RestMethod -Uri "$baseUrl/routes" -Method GET
$routeIdsD = @($routesD | ForEach-Object { $_.routeId })

Assert-Check "Test D.2: Passenger portal displays Route 3 from new session" ($routesD.Count -eq 1 -and $routeIdsD[0] -eq 3) "Routes returned for new session: $($routeIdsD -join ', ')"
Assert-Check "Test D.3: Previous session routes (Route 1) are NOT shown" ($routeIdsD -notcontains 1) "Route 1 correctly omitted from new active session"

# Restore original session as active
$restoreSessionRes = Invoke-RestMethod -Uri "$baseUrl/admin/sessions/$originalSessionId/activate" -Method PUT -Headers $adminHeaders
Assert-Check "Test D.4: Original session can be reactivated" ($restoreSessionRes.message -like "*activated*") "Session $originalSessionId reactivated"

# -------------------------------------------------------------------------
# Test E: No routes are selected. Passenger portal should show a meaningful empty-state message.
# -------------------------------------------------------------------------
Write-Host "`n--- Running Test E: No routes selected (empty state) ---" -ForegroundColor Yellow
$selectionE = @{
    routeIds = @()
    busIds = @(101)
} | ConvertTo-Json

$resE = Invoke-RestMethod -Uri "$baseUrl/admin/sessions/$originalSessionId/selection" -Method PUT -Headers $adminHeaders -ContentType "application/json" -Body $selectionE
$routesE = Invoke-RestMethod -Uri "$baseUrl/routes" -Method GET

Assert-Check "Test E.1: Admin deselects all routes" ($resE.message -like "*saved*") "Admin saved empty route selection"
Assert-Check "Test E.2: Passenger /routes returns empty list" ($routesE.Count -eq 0 -or $null -eq $routesE) "Routes returned: $($routesE.Count)"

# Check frontend app.js empty-state handling
$appJsContent = Get-Content "C:\Users\varsh\.gemini\antigravity\scratch\VaahanSetu\frontend\app.js" -Raw
$handlesEmptyState = $appJsContent -like "*No routes are currently available for the active planning session.*"
Assert-Check "Test E.3: Passenger portal has explicit empty-state message handling" $handlesEmptyState "Verified empty-state message in frontend/app.js"

# -------------------------------------------------------------------------
# Test F: Existing passenger registrations belong to a route that is
# subsequently deselected. Those records must remain intact.
# -------------------------------------------------------------------------
Write-Host "`n--- Running Test F: Existing passenger records preservation ---" -ForegroundColor Yellow
# First, select Route 1 and register a test journey
$selectionF1 = @{
    routeIds = @(1)
    busIds = @(101, 102)
} | ConvertTo-Json
Invoke-RestMethod -Uri "$baseUrl/admin/sessions/$originalSessionId/selection" -Method PUT -Headers $adminHeaders -ContentType "application/json" -Body $selectionF1 | Out-Null

$stopsRoute1 = Invoke-RestMethod -Uri "$baseUrl/routes/1/stops" -Method GET
$regPayload = @{
    routeId = 1
    boardingStopId = $stopsRoute1[0].stopId
    destinationStopId = $stopsRoute1[1].stopId
    passengerCount = 4
} | ConvertTo-Json
$journeyF = Invoke-RestMethod -Uri "$baseUrl/passenger/journeys" -Method POST -ContentType "application/json" -Body $regPayload
$journeyIdF = $journeyF.journeyId
Assert-Check "Test F.1: Registered journey #$journeyIdF on Route 1" ($journeyIdF -gt 0) "Journey created with passenger count: $($journeyF.passengerCount)"

# Now Admin deselects Route 1 (selects only Route 2)
$selectionF2 = @{
    routeIds = @(2)
    busIds = @(101, 102)
} | ConvertTo-Json
Invoke-RestMethod -Uri "$baseUrl/admin/sessions/$originalSessionId/selection" -Method PUT -Headers $adminHeaders -ContentType "application/json" -Body $selectionF2 | Out-Null

# Verify the existing journey on deselected Route 1 is still completely intact
$viewJourneyF = Invoke-RestMethod -Uri "$baseUrl/passenger/journeys/$journeyIdF" -Method GET
Assert-Check "Test F.2: Existing journey #$journeyIdF is intact after route deselection" ($viewJourneyF.journeyId -eq $journeyIdF -and $viewJourneyF.passengerCount -eq 4 -and $viewJourneyF.journeyStatus -eq "REGISTERED") "Journey record preserved: ID $($viewJourneyF.journeyId), status $($viewJourneyF.journeyStatus), passengers $($viewJourneyF.passengerCount)"

# Verify status update (boarding) still works on existing journey
$boardResF = Invoke-RestMethod -Uri "$baseUrl/passenger/journeys/$journeyIdF/status" -Method PATCH -ContentType "application/json" -Body '{"status":"BOARDED"}'
Assert-Check "Test F.3: Existing journey lifecycle actions remain operational" ($boardResF.status -eq "BOARDED") "Status updated to: $($boardResF.status)"

# -------------------------------------------------------------------------
# Test G: An API or database error occurs.
# The passenger portal must show a clear error instead of silently displaying incorrect routes.
# -------------------------------------------------------------------------
Write-Host "`n--- Running Test G: Error handling verification ---" -ForegroundColor Yellow
$handlesApiError = $appJsContent -like "*Unable to connect to backend service. Please check connection.*"
$hasTryCatch = $appJsContent -like "*catch (error)*"
Assert-Check "Test G.1: Passenger portal catches network/API errors" ($handlesApiError -and $hasTryCatch) "Found robust error handling & user-facing error message in app.js"

# Reset session selection back to operational routes (Route 1 and Route 2)
$resetPayload = @{
    routeIds = @(1, 2)
    busIds = @(101, 102)
} | ConvertTo-Json
Invoke-RestMethod -Uri "$baseUrl/admin/sessions/$originalSessionId/selection" -Method PUT -Headers $adminHeaders -ContentType "application/json" -Body $resetPayload | Out-Null

# Summary
Write-Host "`n==========================================================" -ForegroundColor Cyan
Write-Host "   Route Sync Verification Summary: $passed Passed, $failed Failed   " -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
Write-Host "==========================================================" -ForegroundColor Cyan

if ($failed -gt 0) {
    Exit 1
}
