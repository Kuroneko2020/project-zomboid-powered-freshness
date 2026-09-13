-- Java is exposed by the companion ZombieBuddy extension. One shared bridge per VM.
if PoweredFreshnessBridgeLoaded then return end
PoweredFreshnessBridgeLoaded = true

local warned = false
local function ready()
    if PoweredFreshness then return true end
    if not warned then
        warned = true
        print("[PoweredFreshness] Java extension is missing. Install the companion extension on this client/server; freshness reversal is inactive.")
    end
    return false
end

local function start()
    if ready() then PoweredFreshness.onWorldStart() end
end

local function tick()
    if ready() then PoweredFreshness.tick() end
end

local function save()
    if ready() then PoweredFreshness.onWorldSave() end
end

local function stop()
    if ready() then PoweredFreshness.onWorldStop() end
end

local function squareLoaded(square)
    if ready() then PoweredFreshness.onSquareLoaded(square) end
end

local function objectRemoving(object)
    if ready() then PoweredFreshness.onObjectRemoving(object) end
end

local function serverCommand(module, command, args)
    if module == "PoweredFreshness" and isClient() and ready() then
        PoweredFreshness.onServerCommand(command, args)
    end
end

local function clientCommand(module, command, player, args)
    if module == "PoweredFreshness" and isServer() and ready() then
        PoweredFreshness.onClientCommand(player, command, args)
    end
end

-- OnGameStart belongs to the client/single-player world; OnServerStarted to the server.
if isServer() then
    Events.OnServerStarted.Add(start)
    Events.OnClientCommand.Add(clientCommand)
else
    Events.OnGameStart.Add(start)
    Events.OnServerCommand.Add(serverCommand)
    Events.OnDisconnect.Add(stop)
    Events.OnMainMenuEnter.Add(stop)
end
Events.OnTick.Add(tick)
Events.OnSave.Add(save)
Events.LoadGridsquare.Add(squareLoaded)
Events.OnObjectAboutToBeRemoved.Add(objectRemoving)
