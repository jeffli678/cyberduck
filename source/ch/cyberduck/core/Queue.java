package ch.cyberduck.core;

/*
 *  Copyright (c) 2003 David Kocher. All rights reserved.
 *  http://cyberduck.ch/
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Bug fixes, suggestions and comments should be sent to:
 *  dkocher@cyberduck.ch
 */

import ch.cyberduck.core.Message;
import java.util.Observable;
import java.util.Observer;
import java.util.Vector;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Calendar;
import javax.swing.Timer;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import org.apache.log4j.Logger;

/**
* Used to queue multiple connections. <code>queue.start()</code> will
 * start the the connections in the order the have been added to me.
 * Useful for actions where the reihenfolge of the taken actions
 * is important, i.e. deleting directories or uploading directories.
 * @version $Id$
 */
public class Queue extends Observable implements Observer { //Thread {
    private static Logger log = Logger.getLogger(Queue.class);

    public static final int KIND_DOWNLOAD = 0;
    public static final int KIND_UPLOAD = 1;

    /**
	* The elements (jobs to process) of the queue
     */
    private List jobs = new ArrayList();

//    private List completedJobs = new ArrayList();
    /**
	* What kind of queue, either upload or download
     */
    private int kind;
    /**
	* Number of completed jobs in the queue
     */
    private int completedJobs;
    /**
	* The file currently beeing processed in the queue
     */
    private Path candidate;

    /**
	* The root path
     */
//    private List roots;
    private Path root;

    /**
	* The queue has been stopped from processing for any reason
     */
    private boolean stopped;
    /*
     * 	current speed (bytes/second)
     */
    private transient double speed;
    /*
     * overall speed (bytes/second)
     */
    private transient double overall;
    /**
	* The size of all files accumulated
     */
    private int size;
    private int current;

    private int timeLeft;

    Calendar calendar = Calendar.getInstance();
    
    /**
	* @param file The base file to build a queue for. If this is a not a folder
     * the queue will consist of only this.
     * @param  kind Specifiying a download or upload.
     */
//    public Queue(Path parent, int kind) {
//	this(parent, kind);
//  }

    public Queue(Path root, int kind) {
//	this.roots = new ArrayList();
//	this.roots.add(root);
	this.root = root;
	this.kind = kind;
    }

    public int kind() {
	return this.kind;
    }

    public void callObservers(Object arg) {
//	log.debug(this.countObservers()+" observers known.");
        this.setChanged();
	this.notifyObservers(arg);
    }
    
    public void update(Observable o, Object arg) {
	//Forwarding all messages from the current file's status to my observers
	this.callObservers((Message)arg);
    }

    /**
	* @param file Add path to the queue for later processement.
     */
    public void add(Path file) {
	log.info("Adding file to queue:"+file);
        jobs.add(file);
    }

    private String parseTime(int t) {
	if(t > 9) {
	    return String.valueOf(t);
        }
        else {
            return "0" + t;
	}
    }    

    /**
	* Process the queue. All files will be downloaded or uploaded rerspectively.
     * @param resume If false finish all non finished items in the queue. If true refill the queue with all the childs from the parent Path and restart
     */
    public void start(final boolean resume) {
	log.debug("start");
	this.reset();
	new Thread() {
	    public void run() {
		
		Timer clockTimer = new Timer(1000,
			       new ActionListener() {
				   int seconds = 0;
				   int minutes = 0;
				   int hours = 0;
				   public void actionPerformed(ActionEvent event) {
				       seconds++;
				      // calendar.set(year, mont, date, hour, minute, second)
				// >= one hour
				       if(seconds >= 3600) {
					   hours = (int)(seconds/60/60);
					   minutes = (int)((seconds - hours*60*60)/60);
					   calendar.set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DATE), hours, minutes, seconds - minutes*60);
				       }
				       else {
					  // >= one minute
					   if(seconds >= 60) {
					       minutes = (int)(seconds/60);
					       calendar.set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DATE), calendar.get(Calendar.HOUR), minutes, seconds - minutes*60);
					   }
					  // only seconds
					   else {
					       calendar.set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DATE), calendar.get(Calendar.HOUR), calendar.get(Calendar.MINUTE), seconds);
					   }
				       }

				       if(calendar.get(Calendar.HOUR) > 0) {
					   callObservers(new Message(Message.CLOCK, parseTime(calendar.get(Calendar.HOUR)) + ":" + parseTime(calendar.get(Calendar.MINUTE)) + ":" + parseTime(calendar.get(Calendar.SECOND))));
				       }
				       else {
					   Queue.this.callObservers(new Message(Message.CLOCK, parseTime(calendar.get(Calendar.MINUTE)) + ":" + parseTime(calendar.get(Calendar.SECOND))));
				       }
				   }
			       }
			       );
		clockTimer.start();

		/*
		Timer overallSpeedTimer = new Timer(4000,
				      new ActionListener() {
					  Vector overall = new Vector();
					  double current;
					  double last;
					  public void actionPerformed(ActionEvent e) {
					      current = candidate.status.getCurrent();
					      if(current <= 0) {
						  setOverall(0);
					      }
					      else {
						  overall.add(new Double((current - last)/4)); // bytes transferred for the last 4 seconds
						  Iterator iterator = overall.iterator();
						  double sum = 0;
						  while(iterator.hasNext()) {
						      Double s = (Double)iterator.next();
						      sum = sum + s.doubleValue();
						  }
						  setOverall((sum/overall.size()));
						  last = current;
					       //                        log.debug("overallSpeed " + sum/overall.size()/1024 + " KBytes/sec");
					      }
					  }
				      }
				      );
		 */

		Timer currentSpeedTimer = new Timer(500,
				      new ActionListener() {
					  int i = 0;
					  int current;
					  int last;
					  int[] speeds = new int[8];
					  public void actionPerformed(ActionEvent e) {
					      int diff = 0;
					      current = candidate.status.getCurrent();
					      if(current <= 0) {
						  setSpeed(0);
					      }
					      else {
						  speeds[i] = (current - last)*(2); i++; last = current;
						  if(i == 8) { // wir wollen immer den schnitt der letzten vier sekunden
						      i = 0;
						  }

						  for (int k = 0; k < speeds.length; k++) {
						      diff = diff + speeds[k]; // summe der differenzen zwischen einer halben sekunde
						  }
						  
					       //                        log.debug("currentSpeed " + diff/speeds.length/1024 + " KBytes/sec");
						  Queue.this.setSpeed((diff/speeds.length));
					      }

					  }
				      }
				      );

		Timer timeLeftTimer = new Timer(1000,
				  new ActionListener() {
				      public void actionPerformed(ActionEvent e) {
					  if(getSpeed() > 0)
					      Queue.this.setTimeLeft((int)((candidate.status.getSize() - candidate.status.getCurrent())/getSpeed()));
					  else
					      Queue.this.setTimeLeft(-1);
				      }
				  }
				  );

		// --------------------------------------------------------------
		Session session = root.getSession().copy();
		session.addObserver(Queue.this);
		
//		if(!resume || Queue.this.isEmpty()) {
//		    Session session = ((Path)roots.get(0)).getSession().copy();
//		Iterator i = roots.iterator();
//		while(i.hasNext()) {
//		    Path next = (Path)i.next();
		root.fillQueue(Queue.this, session, kind);
		Iterator k = jobs.iterator();
		while(k.hasNext()) {
		    size += ((Path)k.next()).status.getSize();
		}
		
		// --------------------------------------------------------------
		
		//Iterating over all the files in the queue
		Iterator i = jobs.iterator();
		while(i.hasNext() && !isStopped()) {
		    candidate = (Path)i.next();
		    candidate.status.setResume(resume);
		    candidate.status.addObserver(Queue.this);

//		    overallSpeedTimer.start();
		    currentSpeedTimer.start();
		    timeLeftTimer.start();
		    
		    callObservers(new Message(Message.PROGRESS, (KIND_DOWNLOAD == kind ? "Downloading " : "Uploading ") +candidate.getName()+" ("+(completedJobs+1)+" of "+jobs.size()+")"));

		    switch(kind) {
			case KIND_DOWNLOAD:
			    candidate.download();
			    break;
			case KIND_UPLOAD:
			    candidate.upload();
			    break;
		    }
		    if(candidate.status.isComplete()) {
			current += candidate.status.getCurrent();
			completedJobs++;
//			completedJobs.add(candidate);
//			jobs.remove(candidate);
		    }
		    candidate.status.deleteObserver(Queue.this);

//		    overallSpeedTimer.stop();
		    currentSpeedTimer.stop();
		    timeLeftTimer.stop();
		}

		clockTimer.stop();

		if(numberOfJobs() == completedJobs)
		    session.close(); //todo session might be null
		session.deleteObserver(Queue.this);

		stopped = true;
	    }
	}.start();
    }

    public void cancel() {
	this.stopped = true;
	candidate.status.setCanceled(true);
    }

    public boolean isStopped() {
	return stopped;
    }

//    public boolean isEmpty() {
//	return this.remainingJobs() == 0;
  //  }

    /**
	* @return The number of remaining items to be processed in the queue.
     */
//    public int remainingJobs() {
//	log.debug("remainingJobs:"+remainingJobs.size());
//	return jobs.size();
//    }

    /**
	* @return The number of completed (totally transferred) items in the queue.
     */
    public int completedJobs() {
	return this.completedJobs;
//	return completedJobs.size();
    }

    public int numberOfJobs() {
	return this.jobs.size();
    }
//    public int numberOfJobs() {
//	return this.remainingJobs() +this.completedJobs();
  //  }

    public boolean isEmpty() {
	return this.jobs == null || this.numberOfJobs() == 0;
    }

    /**
	* @return The cummulative file size of all files remaining in the queue
     */
    public int getSize() {
//	log.debug("getSize");
//	if(-1 == this.size)
//	    return candidate.status.getSize();
	return this.size;
//	if(-1 == this.totalBytes) {
//	    this.totalBytes = 0;
/*
	int totalBytes = 0;
	Iterator i;
	i = jobs.iterator();
	Path file = null;
	while(i.hasNext()) {
	    file = (Path)i.next();
	    totalBytes += file.status.getSize();
	}
	i = completedJobs.iterator();
	while(i.hasNext()) {
	    file = (Path)i.next();
	    totalBytes += file.status.getSize();
	}
//	log.debug("getSize:"+size);
	return totalBytes;
 */
}

    /**
	* @return The number of bytes already processed.
     */
    public int getCurrent() {
	return this.current + candidate.status.getCurrent();
//	int currentBytes = 0;
//	Iterator i = jobs.iterator();
//	while(i.hasNext()) {
//	    currentBytes += ((Path)i.next()).status.getCurrent();
//	}
//	return currentBytes;
	
//	return candidate.status.getCurrent();
//	int current = 0;
//	Iterator i = files.iterator();
//	Path file = null;
//	while(i.hasNext()) {
//	    file = (Path)i.next();
//	    current = current + file.status.getCurrent();
//	}
//	return current;
    }

    /**
	* @return double current bytes/second
     */
    public double getSpeed() {
	return this.speed;
    }
    
    private void setSpeed(double s) {
	this.speed = s;
	this.callObservers(new Message(Message.DATA, candidate.status));
//	this.callObservers(new Message(Message.SPEED, 
//				Status.parseDouble(this.getSpeed()/1024) + "kB/s, about "
//				+this.getTimeLeft()+" remaining."));
	
//	this.callObservers(new Message(Message.SPEED, "Current: "
//				+ Status.parseDouble(this.getSpeed()/1024) + "kB/s, Overall: "
//				+ Status.parseDouble(this.getOverall()/1024) + " kB/s. "+this.getTimeLeft()));
    }

    private void setTimeLeft(int seconds) {
        this.timeLeft = seconds;
    }

    public String getTimeLeft() {
        String message = "";
        //@todo: implementation of better 'time left' management.
        if(this.timeLeft != -1) {
            if(this.timeLeft >= 60) {
                message = (int)this.timeLeft/60 + " minutes remaining.";
            }
            else {
                message = this.timeLeft + " seconds remaining.";
            }
        }
        return message;
    }
    
    /**
	* @return double bytes per seconds transfered since the connection has been opened
     */
//    private double getOverall() {
//	return this.overall;
//    }

//private void setOverall(double s) {
//    this.overall = s;
//
//    this.callObservers(new Message(Message.SPEED, "Current: "
//				   + Status.parseDouble(this.getSpeed()/1024) + "kB/s, Overall: "
//				   + Status.parseDouble(this.getOverall()/1024) + " kB/s. "+this.getTimeLeft()));
//}

private void reset() {
    this.size = -1;
    this.current = -1;
    this.speed = -1;
    this.overall = -1;
    this.stopped = false;
    this.timeLeft = -1;
    this.completedJobs = 0;
    calendar.set(Calendar.HOUR, 0);
    calendar.set(Calendar.MINUTE, 0);
    calendar.set(Calendar.SECOND, 0);
    
}

  //  public void reset() {
//	log.debug("reset");

//	log.debug("Readding "+completedJobs.size()+" jobs to the queue.");
//	jobs.addAll(0, completedJobs);
    //}
    }