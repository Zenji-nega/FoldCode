//! Smallest possible NativeActivity app: it starts, keeps an event loop
//! running and exits cleanly when Android destroys the activity. It draws
//! nothing, so a successful launch shows a blank window that stays open.

use android_activity::{AndroidApp, MainEvent, PollEvent};

#[no_mangle]
fn android_main(app: AndroidApp) {
    let mut quit = false;
    while !quit {
        app.poll_events(Some(std::time::Duration::from_millis(500)), |event| {
            if let PollEvent::Main(MainEvent::Destroy) = event {
                quit = true;
            }
        });
    }
}
