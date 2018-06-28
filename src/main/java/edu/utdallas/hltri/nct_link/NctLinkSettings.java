package edu.utdallas.hltri.nct_link;

import edu.utdallas.hltri.conf.Config;
import java.nio.file.Path;

public class NctLinkSettings {

  public final Path qrelsPath;

  public NctLinkSettings(Path qrelsPath) {
    this.qrelsPath = qrelsPath;
  }

  static NctLinkSettings fromConfig(Config conf) {
    return new NctLinkSettings(
        conf.getPath("qrels-path")
    );
  }

  public static NctLinkSettings getDefault() {
    return fromConfig(Config.load("nct_link"));
  }
}
