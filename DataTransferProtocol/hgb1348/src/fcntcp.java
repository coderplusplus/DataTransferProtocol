import java.util.*;
import java.io.*;
import java.net.UnknownHostException;

/*
 * This class is y=used to set the client and the server
 */
public class fcntcp {

	String serverAddress = "";
	String file = "";
	int time = 1000;
	boolean quiet=true;
	int port = 0;

	/*
	 * @param args[] This has all the values needed by the client This function
	 * creates a client thread, initializing all the variables required by the
	 * client
	 *
	 */
	public void createClient(String args[]) throws UnknownHostException {
		// if more options given
		if (args.length > 1) {
			for (int i = 1; i < args.length;) {
				if (args[i].equals("-q") || args[i].equals("--quiet")) {
					quiet = false;

				} else if (args[i].equals("-t") || args[i].equals("--timeout")) {
					time = Integer.parseInt(args[i + 1]);
					i++;
				} else if (args[i].equals("-f") || args[i].equals("--file")) {
					file = args[i + 1];
					i++;

				} else if (args[i].contains(".")) {
					serverAddress = args[i];

				} else if (args[i].length() == 4) {
					port = Integer.parseInt(args[i]);

				}
				i++;
			}
		}

		validate("client");
		System.out.println("my file name " + file);
		System.out.println("Client!");
		new client(file, time, quiet, serverAddress, port).start();

	}

	/*
	 * @param args[] This has all the values needed by the server This function
	 * creates a server thread, initializing all the variables required by the
	 * server
	 *
	 */
	public void createServer(String args[]) {

		// if more options given
		if (args.length > 1) {
			for (int i = 1; i < args.length; i++) {
				if (args[i].equals("-q") || args[i].equals("--quiet")) {
					quiet = false;

				} else if (args[i].length() == 4) {
					port = Integer.parseInt(args[i]);

				}
			}
		}
		validate("server");
		System.out.println("Sever!");
		new server(quiet, port).start();
	}

	/*
	 * @param args[] This has all the values needed to decide client,server and
	 * proxy This function creates a client,server or proxy based on the user
	 * input
	 *
	 */
	public static void main(String args[]) throws UnknownHostException {
		fcntcp mainObject = new fcntcp();
		if (args.length == 0) {
			System.out.println("Oops! Specify the operation: Run as client -c\n Run as Server -s \n");
			System.exit(0);
		} else {
			if (args[0].equals("-c") || args[0].equals("-s")) {
				// for client
				if (args[0].equals("-c")) {
					mainObject.createClient(args);
				}
				// for server
				else {
					mainObject.createServer(args);
				}

			} else {
				System.out
						.println("Oops! Specify the operation: Run as client -c\n Run as Server -s \n Run as Proxy -p");
				System.exit(0);
			}
		}
	}

	// validations
	/*
	 * @param name This variable decides the operator type This function
	 * validates all the information provided. Checks if the port, server address, etc
	 * are valid and if mandatory fields are provided by the user.
	 *
	 */

	public void validate(String name) {

		// default port for all
		if (port == 0) {
			System.out.println("Oops! You need to enter the port number.");
			System.exit(0);
		}

		// mandatory server address for the client to connect to the server
		if (name.equals("client")) {
			if (serverAddress.length() == 0) {
				System.out.println("Oops! You need to enter the Server Address!");
				System.exit(0);
			}
			if (file.length() == 0) {
				System.out.println("Oops! You need to enter the file path!");
				System.exit(0);
			}
		}
	}

}
