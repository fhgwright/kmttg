package com.tivo.kmttg.task;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Serializable;
import java.util.Date;
import java.util.Stack;

import com.tivo.kmttg.main.auto;
import com.tivo.kmttg.main.config;
import com.tivo.kmttg.main.jobData;
import com.tivo.kmttg.main.jobMonitor;
import com.tivo.kmttg.util.backgroundProcess;
import com.tivo.kmttg.util.debug;
import com.tivo.kmttg.util.file;
import com.tivo.kmttg.util.log;
import com.tivo.kmttg.util.string;

public class download_decrypt implements Serializable {
   private static final long serialVersionUID = 1L;
   String command = "";
   String cookieFile = "";
   String script = "";
   private backgroundProcess process;
   public jobData job;
   
   public download_decrypt(jobData job) {
      debug.print("job=" + job);
      this.job = job;
      
      // Generate unique cookieFile and script names
      cookieFile = file.makeTempFile("cookie");
      script = file.makeTempFile("script");
      if (config.OS.equals("windows"))
         script += ".bat";
   }
   
   public backgroundProcess getProcess() {
      return process;
   }
   
   public Boolean launchJob() {
      debug.print("");
      Boolean schedule = true;
      
      // Don't decrypt if mpegFile already exists
      if ( file.isFile(job.mpegFile) ) {
         if (config.OverwriteFiles == 0) {
            log.warn("SKIPPING DOWNLOAD/DECRYPT, FILE ALREADY EXISTS: " + job.mpegFile);
            schedule = false;
         } else {
            log.warn("OVERWRITING EXISTING FILE: " + job.mpegFile);
         }
      }
      
      if ( ! file.isFile(config.tivodecode) ) {
         log.error("tivodecode not found: " + config.tivodecode);
         schedule = false;
      }
      
      if ( ! file.isFile(config.curl) ) {             
         log.error("curl not found: " + config.curl);
         schedule = false;
      }
      
      if (schedule) {
         // Create sub-folders for output file if needed
         if ( ! jobMonitor.createSubFolders(job.mpegFile, job) ) schedule = false;
      }
      
      if (schedule) {
         if ( start() ) {
            job.process_download_decrypt = this;
            jobMonitor.updateJobStatus(job, "running");
            job.time             = new Date().getTime();
         }
         return true;
      } else {
         return false;
      }      
   }
   
   private Boolean start() {
      debug.print("");
      if (job.url == null || job.url.length() == 0) {
         log.error("URL not given");
         jobMonitor.removeFromJobList(job);
         file.delete(cookieFile);
         file.delete(script);
         return false;
      }
      
      // Add wan http port if configured
      String wan_port = config.getWanSetting(job.tivoName, "http");
      if (wan_port != null)
         job.url = string.addPort(job.url, wan_port);
      
      String url = job.url;
      if (config.TSDownload == 1)
         url += "&Format=video/x-tivo-mpeg-ts";

      // Make main piped command string
      command = "\"" + config.curl + "\" ";
      if (config.OS.equals("windows"))
         command += "--retry 3 ";
      command += "--anyauth --globoff --user tivo:" + config.MAK + " ";
      command += "--insecure --cookie-jar \"" + cookieFile + "\" --url \"" + url + "\" ";
      command += "| " + "\"" + config.tivodecode + "\" --mak " + config.MAK + " --out ";
      command += "\"" + job.mpegFile + "\" -";
      
      // Make temporary script containing command
      try {
         BufferedWriter ofp = new BufferedWriter(new FileWriter(script));
         ofp.write(command);
         if (config.OS.equals("windows"))
            ofp.write("\r");
         ofp.write("\n");
         ofp.close();
      } catch (IOException e) {
         log.error(e.toString());
         return false;
      }

      // Execute above script in native OS shell
      Stack<String> c = new Stack<String>();      
      if (config.OS.equals("windows")) {
         c.add("cmd.exe");
         c.add("/c");
      } else {
         c.add("sh");
      }
      c.add(script);
      process = new backgroundProcess();            
      log.print(">> DOWNLOADING/DECRYPTING TO " + job.mpegFile + " ...");
      if ( process.run(c) ) {
         log.print(command);
      } else {
         log.error("Failed to start command: " + command);
         process.printStderr();
         process = null;
         jobMonitor.removeFromJobList(job);
         return false;
      }
      return true;
   }
   
   public void kill() {
      debug.print("");
      process.kill();
      log.warn("Killing '" + job.type + "' job: " + command);
      file.delete(cookieFile);
      file.delete(script);
   }
   
   // Check status of a currently running job
   // Returns true if still running, false if job finished
   // If job is finished then check result
   public Boolean check() {
      //debug.print("");
      int exit_code = process.exitStatus();
      if (exit_code == -1) {
         // Still running
         if (config.GUIMODE && file.isFile(job.mpegFile)) {
            // Update status in job table
            Long size = file.size(job.mpegFile);
            String s = String.format("%.2f MB", (float)size/Math.pow(2,20));
            String t = jobMonitor.getElapsedTime(job.time);
            int pct = Integer.parseInt(String.format("%d", size*100/job.tivoFileSize));
            
            // Calculate current transfer rate over last dt msecs
            Long dt = (long)5000;
            job.time2 = new Date().getTime();
            job.size2 = size;
            if (job.time1 == null)
               job.time1 = job.time2;
            if (job.size1 == null)
               job.size1 = job.size2;
            if (job.time2-job.time1 >= dt && job.size2 > job.size1) {
               job.rate = getRate(job.size2-job.size1, job.time2-job.time1);
               job.time1 = job.time2;
               job.size1 = job.size2;
            }
            
            if (config.download_time_estimate == 1) {
               // Estimated time remaining
               job.rate = string.getTimeRemaining(job.time2, job.time, job.tivoFileSize, size);
            }
            
            if ( jobMonitor.isFirstJobInMonitor(job) ) {
               // Update STATUS column 
               config.gui.jobTab_UpdateJobMonitorRowStatus(job, t + "---" + s + "---" + job.rate);
               
               // If 1st job then update title & progress bar
               String title = String.format("download: %d%% %s", pct, config.kmttg);
               config.gui.setTitle(title);
               config.gui.progressBar_setValue(pct);
            } else {
               // Update STATUS column            
               config.gui.jobTab_UpdateJobMonitorRowStatus(job, String.format("%d%%",pct) + "---" + s + "---" + job.rate);
            }
         }
         return true;
      } else {
         // Job finished
         if (config.GUIMODE) {
            if ( jobMonitor.isFirstJobInMonitor(job) ) {
               config.gui.setTitle(config.kmttg);
               config.gui.progressBar_setValue(0);
            }
         }
         
         jobMonitor.removeFromJobList(job);
         
         // Check for problems
         int failed = 0;
         
         // exit code != 0 => trouble
         if (exit_code != 0) {
            failed = 1;
         }
         
         // No or empty output means problems         
         if ( file.isEmpty(job.mpegFile) ) {
            failed = 1;
         } else {
            // Print statistics for the job
            String s = String.format("%.2f MB", file.size(job.mpegFile)/Math.pow(2,20));
            String t = jobMonitor.getElapsedTime(job.time);
            String r = jobMonitor.getRate(file.size(job.mpegFile), job.time);
            log.warn(job.mpegFile + ": size=" + s + " elapsed=" + t + " (" + r + ")");
         }
         
         // If file size is very small then it's likely a failure
         if (failed == 0) {
            if ( file.size(job.mpegFile) < 1000 ) failed = 1;
         }
         
         if (failed == 1) {
            log.error("Download failed to file: " + job.mpegFile);
            log.error("Exit code: " + exit_code);
            process.printStderr();
            file.delete(job.mpegFile);
            
            // Try download again with delayed launch time if specified
            if (job.launch_tries < config.download_tries) {
               job.launch_tries++;
               log.warn(string.basename(job.mpegFile) + ": Download attempt # " +
                     job.launch_tries + " scheduled in " + config.download_retry_delay + " seconds.");
               job.launch_time = new Date().getTime() + config.download_retry_delay*1000;
               jobMonitor.submitNewJob(job);
            } else {
               log.error(string.basename(job.mpegFile) + ": Too many failed downloads, GIVING UP!!");
            }
         } else {
            log.print("---DONE--- job=" + job.type + " output=" + job.mpegFile);
            // Add auto history entry if auto downloads configured
            if (file.isFile(config.autoIni))
               auto.AddHistoryEntry(job);
         }
      }
      file.delete(cookieFile);
      file.delete(script);
      
      return false;
   }

   // Return rate in Mbps (ds=delta bytes, dt=delta time in msecs)
   private String getRate(long ds, long dt) {      
      return String.format("%.1f Mbps", (ds*8000)/(1e6*dt));
   }

}
