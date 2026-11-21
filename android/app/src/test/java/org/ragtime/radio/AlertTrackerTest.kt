package org.ragtime.radio

import org.junit.Assert.*
import org.junit.Test

/** What is worth a notification, and what never is. */
class AlertTrackerTest {
    private fun line(id:Long,from:String,text:String,to:String="",ours:Boolean=false,kind:Int=0,past:Boolean=false)=ChatRow(id,from,text,to,ours,0,"",kind,past)
    private fun offer(key:String,name:String,receive:Boolean=true)=TransferRow(key,"Session",name,"K7QTH","Offered","",1200,0,0,false,true,false,receive,receive,false,false,false,"")
    private val net=RadioSnapshot(ready=true,dataUp=true)

    @Test fun whatWasThereAtTheStartAlertsNobody() {
        val tracker=AlertTracker()
        val history=net.copy(broadcast=listOf(line(1,"W1QSO","an old line to me","N0DEMO")),phase=2,peer="K7QTH",incoming=true,
            session=listOf(line(5,"K7QTH","said before")),files=listOf(offer("s:1","old.txt")))
        assertTrue(tracker.update(history,"N0DEMO").isEmpty())
        assertTrue(tracker.update(history,"N0DEMO").isEmpty())
    }

    @Test fun aLineAddressedToTheOperatorAlertsAndOthersDoNot() {
        val tracker=AlertTracker()
        tracker.update(net,"N0DEMO")
        val heard=net.copy(broadcast=listOf(line(1,"W1QSO","CQ CQ"),line(2,"K7QTH","for someone else","DL3QRM"),
            line(3,"N0DEMO","my own line","W1QSO",ours=true),line(4,"W1QSO","good evening","N0DEMO"),line(5,"F5QRN","a file card","N0DEMO",kind=1)))
        val alerts=tracker.update(heard,"N0DEMO")
        assertEquals(listOf(Alert("b:W1QSO","W1QSO on D2G","good evening","Broadcast")),alerts)
        assertTrue("told once",tracker.update(heard,"N0DEMO").isEmpty())
    }

    @Test fun anIncomingCallAlertsAndOurOwnCallDoesNot() {
        val tracker=AlertTracker()
        tracker.update(net,"N0DEMO")
        assertTrue(tracker.update(net.copy(active=true,phase=1,peer="K7QTH"),"N0DEMO").isEmpty())
        assertTrue("we called",tracker.update(net.copy(active=true,phase=2,peer="K7QTH",incoming=false),"N0DEMO").isEmpty())
        tracker.update(net,"N0DEMO")
        val called=tracker.update(net.copy(active=true,phase=2,peer="DL3QRM",incoming=true),"N0DEMO")
        assertEquals(1,called.size)
        assertTrue(called[0].call)
        assertEquals("DL3QRM called you",called[0].title)
        assertEquals("Ragchew",called[0].open)
        assertTrue("once a ragchew",tracker.update(net.copy(active=true,phase=2,peer="DL3QRM",incoming=true),"N0DEMO").isEmpty())
    }

    @Test fun aRagchewLineAlertsButNotWhatItsLogReadBack() {
        val tracker=AlertTracker()
        tracker.update(net,"N0DEMO")
        // A new ragchew reads the last one's lines back with new numbers.
        val started=net.copy(active=true,phase=2,peer="K7QTH",incoming=false,
            session=listOf(line(10,"K7QTH","from last week",past=true),line(11,"N0DEMO","mine",ours=true,past=true)))
        assertTrue(tracker.update(started,"N0DEMO").isEmpty())
        val said=started.copy(session=started.session+line(12,"K7QTH","Hello, how copy?")+line(1L shl 62,"K7QTH","note.txt: Saved",kind=1))
        assertEquals(listOf(Alert("s:K7QTH","K7QTH","Hello, how copy?","Ragchew")),tracker.update(said,"N0DEMO"))
        assertTrue(tracker.update(said.copy(session=said.session+line(13,"N0DEMO","fine",ours=true)),"N0DEMO").isEmpty())
        assertEquals(1,tracker.update(said.copy(session=said.session+line(14,"K7QTH","73")),"N0DEMO").size)
    }

    @Test fun aFileOfferAlertsOnce() {
        val tracker=AlertTracker()
        tracker.update(net,"N0DEMO")
        val offered=net.copy(files=listOf(offer("s:7","bulletin.txt")))
        val alerts=tracker.update(offered,"N0DEMO")
        assertEquals("K7QTH offers bulletin.txt",alerts.single().title)
        assertEquals("Files",alerts.single().open)
        assertTrue(tracker.update(offered,"N0DEMO").isEmpty())
        // Taken or declined, it is no longer an offer; a broadcast file never alerts.
        assertTrue(tracker.update(net.copy(files=listOf(offer("s:7","bulletin.txt",receive=false),
            offer("b:9","news.txt").copy(via="Broadcast"))),"N0DEMO").isEmpty())
    }

    @Test fun aNewConnectionStartsFromWhatItFinds() {
        val tracker=AlertTracker()
        tracker.update(net.copy(broadcast=listOf(line(40,"W1QSO","hello","N0DEMO"))),"N0DEMO")
        tracker.reset()
        // Another station's log, numbered from 1 again.
        assertTrue(tracker.update(net.copy(broadcast=listOf(line(1,"W1QSO","old","N0DEMO"))),"N0DEMO").isEmpty())
        assertEquals(1,tracker.update(net.copy(broadcast=listOf(line(1,"W1QSO","old","N0DEMO"),line(2,"W1QSO","new","N0DEMO"))),"N0DEMO").size)
    }
}
