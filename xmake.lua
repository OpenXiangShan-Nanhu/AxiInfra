---@diagnostic disable: undefined-global, undefined-field

-- Supported -M / --main-function values (maps to generator.<Name>Top):
--   Lmss, AxiBridgeCfg, AxiReorder, AxiBuffer, AxiBufferChain,
--   AxiFieldAdapter, AxiNarrowToWide, AxiWideToNarrow,
--   AxiErrorDevice, AxiLite2Axi, AxiAsyncSource, AxiAsyncSink

task("rtl", function()
  set_menu {
    usage = "xmake rtl [options]",
    description = "Generate RTL; use -M to select module top (default: Lmss)",
    options = {
        { 'b', "build-dir",     "kv", "build", "build directory" },
        { 'M', "main-function", "kv", "Lmss",  "main class suffix; runs generator.<Name>Top" },
    }
  }
  on_run(function()
    os.cd(os.scriptdir())
    import("core.base.option")
    local main = option.get("main-function") or "Lmss"
    local build_dir = option.get("build-dir") or "build"
    -- Per-module output dir so different -M builds do not clobber each other.
    -- Keep historical paths for the two existing tops used by parent projects.
    local rtl_subdir = "rtl"
    if main == "AxiBridgeCfg" then
      rtl_subdir = "rtl_bridge_cfg"
    elseif main ~= "Lmss" then
      rtl_subdir = "rtl_" .. main
    end
    local rtl_dir = path.join(build_dir, rtl_subdir)

    local chisel_opts = { "-i", "test.runMain", "generator." .. main .. "Top" }
    table.join2(chisel_opts, {
      "--throw-on-first-error",
      "--target", "systemverilog",
      "--split-verilog",
      "--full-stacktrace",
      "-td", rtl_dir
    })

    if os.exists(rtl_dir) then os.tryrm(rtl_dir) end

    if os.host() == "windows" then
      os.execv("powershell", table.join({ "mill" }, chisel_opts))
    else
      os.execv("mill", chisel_opts)
    end
    os.tryrm(path.join(rtl_dir, "firrtl_black_box_resource_files.f"))
    os.tryrm(path.join(rtl_dir, "filelist.f"))
    os.tryrm(path.join(rtl_dir, "extern_modules.sv"))
  end)
end)

-- Backward-compatible wrapper used by parent TestAXIXBar/xmake.lua
task("rtl_bridge_cfg", function()
  set_menu {
    usage = "xmake rtl_bridge_cfg [options]",
    description = "Generate AxiBridgeCfg RTL (alias of: xmake rtl -M AxiBridgeCfg)",
    options = {
        { 'b', "build-dir", "kv", "build", "build directory" },
    }
  }
  on_run(function()
    import("core.base.option")
    import("core.base.task")
    task.run("rtl", {
      ["main-function"] = "AxiBridgeCfg",
      ["build-dir"] = option.get("build-dir") or "build",
    })
  end)
end)

task("init", function()
    on_run(function()
        os.cd(os.scriptdir())
        os.exec("git submodule update --init")
    end)
    set_menu {
        options = {} -- If no options required, just set it to {} and DO NOT remove this line. (`options` key is required)
    }
end)

task("comp", function()
    on_run(function()
        os.cd(os.scriptdir())
        if os.host() == "windows" then
            os.execv("powershell", { "mill", "-i", "compile" })
            os.execv("powershell", { "mill", "-i", "test.compile" })
        else
            os.execv("mill", { "-i", "compile" })
            os.execv("mill", { "-i", "test.compile" })
        end
    end)
    set_menu {
        options = {} -- If no options required, just set it to {} and DO NOT remove this line. (`options` key is required)
    }
end)

task("idea", function()
    on_run(function()
        os.cd(os.scriptdir())
        if os.host() == "windows" then
            os.execv("powershell", { "mill", "-i", "mill.idea.GenIdea/idea" })
        else
            os.execv("mill", { "-i", "mill.idea.GenIdea/idea" })
        end
    end)
    set_menu {
        options = {}
    }
end)
