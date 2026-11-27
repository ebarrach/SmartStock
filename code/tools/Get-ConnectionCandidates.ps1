param([int]$Port=18119,[string]$InterfaceAlias,[string]$Address)
$adapters=Get-NetAdapter | Where-Object {$_.Status -eq 'Up'}
if($InterfaceAlias){$adapters=$adapters | Where-Object {$_.Name -eq $InterfaceAlias}}
elseif(!$Address){$adapters=$adapters | Where-Object {$_.HardwareInterface -and $_.InterfaceDescription -notmatch 'VPN|Virtual|Hyper-V|TAP|Tunnel|Nord'}}
$candidates=@(foreach($adapter in $adapters){
  $profile=Get-NetConnectionProfile -InterfaceIndex $adapter.ifIndex -ErrorAction SilentlyContinue
  Get-NetIPAddress -InterfaceIndex $adapter.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object {$_.AddressState -eq 'Preferred' -and $_.IPAddress -notmatch '^(127\.|169\.254\.)' -and (!$Address -or $_.IPAddress -eq $Address)} |
    ForEach-Object {[PSCustomObject]@{Interface=$adapter.Name;IPv4=$_.IPAddress;Profil=($profile.NetworkCategory -join ',');URL="http://$($_.IPAddress):$Port";Etat='Candidate : acces telephone non verifie'}}
})
if(($InterfaceAlias -or $Address) -and !$candidates.Count){throw 'Interface/adresse selectionnee absente ou inactive.'}
$candidates
