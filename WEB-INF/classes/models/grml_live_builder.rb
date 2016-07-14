class GrmlLiveBuilder < Jenkins::Tasks::Builder

  display_name "grml-live"
  attr_accessor :arch, :classes, :name, :suite, :output_directory, :version, :version_from_date, :extract_iso, :build_only, :codename

  def options
    [:arch, :classes, :name, :suite, :output_directory, :version, :version_from_date, :extract_iso, :build_only, :codename]
  end

  def initialize(attrs)
    options.each do |sym|
      send(sym.to_s + '=', attrs[sym.to_s])
    end
  end

  def prebuild(build, listener)
    # strip all options
    options.each do |sym|
      next if send(sym.to_s).nil?
      if send(sym.to_s).respond_to?(:strip) and send(sym.to_s).strip.empty?
        send(sym.to_s + '=', nil)
      end
    end
  end

  def perform(build, launcher, listener)
    workingpath = build.workspace.realpath

    cmd = ["sudo", "-A", "grml-live", "-F", "-V", "-A"]
    cmd << ["-a", @arch] if @arch
    cmd << ["-c", @classes] if @classes
    cmd << ["-s", @suite] if @suite
    cmd << ["-b"] if @build_only
    cmd << ["-e", @extract_iso] if @extract_iso

    user = build.env['LOGNAME'] || build.env['USER'] || build.env['GRML_LIVE_USER']
    cmd << ["-U", user] if user

    if version
      ver = version
    elsif version_from_date
      ver = Time.now.strftime "%Y%m%d"
    else
      ver = "build%s" % build.env['BUILD_NUMBER']
    end
    cmd << ["-v", ver]
    cmd << ["-r", codename || "autobuild-%s" % ver]

    name_ = @name || "autobuild"
    cmd << ["-g", name_]

    if @output_directory
      cmd << ["-o", @output_directory] 
    else
      cmd << ["-o", build.env['WORKSPACE']]
    end

    #p cmd
    cmd << {:out => listener, :chdir => workingpath}
    cmd.flatten!

    #listener.info "build_vars: #{build.build_var.inspect}"

    if launcher.execute(*cmd) == 0
      listener.info "Running grml-live was successful."
    else
      listener.error "Fatal error while running grml-live. :("
      build.halt("Build failed.")
    end
  end
end
