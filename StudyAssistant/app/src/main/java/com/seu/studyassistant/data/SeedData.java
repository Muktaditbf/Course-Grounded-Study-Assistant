package com.seu.studyassistant.data;

/**
 * Demo content for the Course-Grounded Study Assistant.
 * Courses are the student's real Fall semester registration at Southeast University.
 * Faculty names are shown as printed on the course list; no personal emails are stored.
 *
 * Materials marked approved=false exist so the Teacher-Enforced Content Lock
 * (PDD Section 6.1 / SRS FR 3.2 / NFR 14.1) can be demonstrated live.
 */
public final class SeedData {

    private SeedData() {}

    /** code | title | faculty | schedule | joinCode */
    public static final String[][] COURSES = {
        {"CSE346.14", "Information System Design & Software Engineering Lab", "Radiathun Tasnia [RADT]", "THU 13:30-15:30, Room SEU609", "ISD346"},
        {"CSE383.2", "Database Design", "Namirah Rasul [MNAR]", "SUN & TUE 08:30-09:50, Room SEU527", "DBD383"},
        {"CSE384.6", "Database Design Lab", "Mahjabin Sultana [MHSU]", "THU 08:00-10:00, Room SEU612", "DBL384"},
        {"CSE341.8", "Computer Networking", "Mst. Jesiara Khatun [JAR]", "MON & WED 11:30-12:50, Room SEU406", "NET341"},
        {"CSE342.14", "Computer Networking Lab", "Md. Safwan Zaher Asif [MSZA]", "WED 16:30-18:30, Room SEU804", "NTL342"},
        {"CSE365.11", "Artificial Intelligence", "Nafisa Khan Youkee [NKY]", "MON & WED 13:30-14:50, Room SEU321", "AIN365"},
        {"ETE282.15", "Communication Lab", "Md. Mushfiqur Rahman Fakir [MMRF]", "THU 11:30-13:30, Room SEU611", "COM282"}
    };

    /** courseCode | title | type | approved(1/0) | body */
    public static final String[][] MATERIALS = {

        {"CSE346.14", "Lecture 1: Software Process Models", "lecture", "1",
         "A software process model is an abstract representation of the activities required to build a software system. "
         + "The waterfall model arranges requirements analysis, design, implementation, testing, deployment and maintenance as "
         + "strictly sequential phases, where each phase must be signed off before the next begins. It suits projects whose "
         + "requirements are stable and well understood, but it responds poorly to change because returning to an earlier phase "
         + "is expensive. Incremental development builds the system in small slices, delivering working functionality early and "
         + "refining it with user feedback. The spiral model organises development into repeated cycles, and every cycle begins "
         + "with explicit risk assessment, which makes it suitable for large or uncertain projects. Agile methods such as Scrum "
         + "favour short iterations called sprints, continuous customer involvement and working software over comprehensive "
         + "documentation. Choosing a process model depends on requirement stability, project size, team experience and the cost "
         + "of failure."},

        {"CSE346.14", "Lecture 2: Software Requirements Specification", "lecture", "1",
         "A Software Requirements Specification, abbreviated SRS, is a complete description of the behaviour of the system to be "
         + "developed. It establishes an agreement between the customer and the development team about what the product must do "
         + "and what it is not expected to do. Functional requirements describe the services the system must provide, how it must "
         + "react to particular inputs, and how it must behave in particular situations. Non-functional requirements are constraints "
         + "on the services offered, such as performance, security, reliability, usability and legal compliance. A good requirement "
         + "is correct, unambiguous, complete, consistent, verifiable, traceable and ranked for importance. Requirements are gathered "
         + "through interviews, questionnaires, observation, document analysis and prototyping. The standard structure of an SRS "
         + "includes a preface, an introduction, a glossary, the user requirement definition, the system architecture, the detailed "
         + "requirements specification, system models, and an evolution section describing anticipated future change."},

        {"CSE346.14", "Lecture 3: Use Case Modelling", "lecture", "1",
         "Use case modelling describes the interaction between users and a system in order to achieve a goal. An actor is any person, "
         + "organisation or external system that interacts with the software; actors are drawn outside the system boundary. A use case "
         + "is a single unit of meaningful work, written as a verb phrase such as Login to the System or Upload Course Material. Each "
         + "use case description records the actors involved, the preconditions that must hold before it starts, the main success "
         + "scenario written as numbered steps, the postcondition describing the state after successful completion, and the alternative "
         + "courses that handle failure or exception paths. Relationships between use cases include include, which factors out shared "
         + "behaviour, extend, which adds optional behaviour, and generalisation. A use case diagram shows actors, use cases and their "
         + "associations inside a system boundary rectangle. Use cases are valuable because they express requirements in language that "
         + "non-technical stakeholders can review and confirm."},

        {"CSE346.14", "Lab 1: Cost Estimation and Feasibility Study", "lab", "1",
         "Cost finding determines whether a proposed information system is worth building. Development cost is estimated by breaking "
         + "the project into work items such as user interface design, frontend development, backend and API development, database "
         + "setup, testing and quality assurance, and deployment, then costing each item. Feasibility is assessed along four dimensions. "
         + "Technical feasibility asks whether the technology and skills exist. Economic feasibility asks whether benefits exceed costs. "
         + "Operational feasibility asks whether the organisation can actually adopt and run the system. Schedule feasibility asks whether "
         + "the timeline is achievable. Investment analysis uses the present value formula P equals F divided by one plus I raised to the "
         + "power n, where P is present value, F is the future net cash flow, I is the discount rate and n is the year number. The payback "
         + "period is the point at which cumulative present value equals the initial investment. Net present value is cumulative present "
         + "value minus initial investment, and return on investment is net profit divided by initial investment expressed as a percentage."},

        {"CSE346.14", "Assignment 1: Problem Definition Document", "assignment", "1",
         "The Problem Definition Document states the problem a proposed system will solve before any design work begins. It opens with an "
         + "introduction giving the background and context of the domain. The problem statement then describes precisely what is wrong with "
         + "the current situation, who suffers from it, and why existing tools do not solve it. Objectives are written as specific measurable "
         + "goals rather than vague intentions. The preliminary solution lists the major capabilities the proposed system will offer without "
         + "committing to implementation detail. Project scope is defined across budget, time, function, features and facilities, and it must "
         + "state clearly what is out of scope so expectations remain controlled. A unique features section explains what distinguishes the "
         + "proposal from existing products, ideally supported by a comparison table. The document closes with an estimated cost and time for "
         + "the feasibility study, followed by a conclusion. Submit your document as a PDF through the course portal before the deadline."},

        {"CSE346.14", "Quiz 1: Software Development Life Cycle", "quiz", "1",
         "Question one. List the phases of the waterfall model in order and state one advantage and one disadvantage of the model. Question two. "
         + "Distinguish between functional and non-functional requirements and give two examples of each for a library management system. "
         + "Question three. Define verification and validation and explain the difference between them using the phrases building the product "
         + "right and building the right product. Question four. What is the purpose of a feasibility study and what are its four dimensions. "
         + "Question five. Explain the terms actor, precondition, main success scenario and alternative course as used in a use case description. "
         + "Question six. A project requires an initial investment of six hundred thousand taka and returns net cash flows over four years at a "
         + "discount rate of twelve percent. Explain how you would compute the payback period using cumulative present value and linear "
         + "interpolation between the two bracketing years."},

        {"CSE346.14", "Notes: Data Flow Diagrams", "notes", "0",
         "A data flow diagram, abbreviated DFD, models how data moves through an information system. It uses only four symbols. A process, drawn "
         + "as a circle or rounded rectangle, transforms incoming data into outgoing data. A data flow, drawn as an arrow, shows data moving "
         + "between components and is labelled with the data it carries. A data store, drawn as an open rectangle or two parallel lines, is a "
         + "repository where data rests between processes. An external entity, drawn as a square, is a source or sink of data lying outside the "
         + "system boundary. The context diagram, also called the level zero DFD, shows the entire system as one process with its external "
         + "entities, establishing the system boundary. Level one explodes that single process into its major subprocesses. Balancing requires "
         + "that the data flows entering and leaving a child diagram match those of its parent process exactly. A DFD shows what data moves and "
         + "where it goes, but deliberately shows no control logic, no decisions, no loops and no timing."},

        {"CSE346.14", "Lecture 4: Software Testing Fundamentals", "lecture", "0",
         "Software testing evaluates a system to detect the difference between expected and actual behaviour. Unit testing exercises the smallest "
         + "testable component, usually a single function or class, in isolation from the rest of the system. Integration testing checks that "
         + "separately developed modules cooperate correctly across their interfaces. System testing validates the assembled product against the "
         + "specification as a whole. Acceptance testing is performed by or on behalf of the customer to decide whether the delivered system is "
         + "acceptable. Black box testing derives test cases from the specification without knowledge of internal structure, using techniques such "
         + "as equivalence partitioning and boundary value analysis. White box testing uses knowledge of internal code structure to design tests "
         + "that achieve statement, branch or path coverage. Regression testing re-runs previously passing tests after a change to confirm that "
         + "nothing already working has broken. A test case records its identifier, precondition, input, expected output and actual result."},

        {"CSE383.2", "Lecture 1: Entity Relationship Modelling", "lecture", "1",
         "An entity relationship model describes the data requirements of a system independently of how the data will be stored. An entity is a "
         + "distinguishable object in the domain, such as Student or Course, and is drawn as a rectangle. An attribute is a property of an entity "
         + "and is drawn as an ellipse; the attribute or set of attributes that uniquely identifies each instance is the primary key and is "
         + "underlined. A relationship, drawn as a diamond, associates two or more entities and carries a cardinality ratio which may be one to "
         + "one, one to many, many to one, or many to many. Participation may be total, drawn with a double line, meaning every instance must take "
         + "part, or partial, drawn with a single line. A weak entity has no key of its own and depends on an owner entity through an identifying "
         + "relationship. When mapping to relational tables, each strong entity becomes a table, each many to many relationship becomes a separate "
         + "junction table holding the keys of both participants, and one to many relationships place a foreign key on the many side."},

        {"CSE383.2", "Lecture 2: Normalization", "lecture", "1",
         "Normalization organises the columns and tables of a relational database to reduce redundancy and eliminate update, insertion and deletion "
         + "anomalies. A relation is in first normal form when every attribute holds only atomic values and there are no repeating groups. A relation "
         + "is in second normal form when it is in first normal form and every non key attribute is fully functionally dependent on the whole primary "
         + "key, which removes partial dependency and matters only for composite keys. A relation is in third normal form when it is in second normal "
         + "form and no non key attribute depends on another non key attribute, which removes transitive dependency. Boyce Codd normal form is a "
         + "stricter version of third normal form requiring that every determinant is a candidate key. A functional dependency written A determines B "
         + "means that each value of A is associated with exactly one value of B. Decomposition must be lossless, meaning the original relation can be "
         + "recovered by a natural join, and should preserve dependencies where possible."},

        {"CSE384.6", "Lab 2: SQL Joins and Subqueries", "lab", "1",
         "A join combines rows from two or more tables using a related column. An inner join returns only the rows having a match in both tables. A "
         + "left outer join returns every row from the left table together with matching rows from the right table, substituting null where no match "
         + "exists, and a right outer join does the reverse. A full outer join returns unmatched rows from both sides. A cross join produces the "
         + "cartesian product of the two tables. A self join joins a table to itself using table aliases, which is useful for hierarchical data such "
         + "as an employee table containing a manager identifier. A subquery is a query nested inside another query; it may appear in the select list, "
         + "the from clause or the where clause. A correlated subquery references a column from the outer query and is therefore evaluated once per "
         + "outer row. The exists operator tests whether a subquery returns any row at all. Aggregate functions such as count, sum, avg, min and max "
         + "combine with group by, and the having clause filters groups after aggregation while where filters rows before it."},

        {"CSE341.8", "Lecture 1: The OSI Reference Model", "lecture", "1",
         "The Open Systems Interconnection model divides network communication into seven layers, each providing services to the layer above it. The "
         + "physical layer transmits raw bits over a medium and defines voltages, connectors and timing. The data link layer groups bits into frames, "
         + "provides node to node delivery, performs error detection using a cyclic redundancy check, and controls access to the shared medium through "
         + "its media access control sublayer. The network layer moves packets between hosts across multiple networks and is responsible for logical "
         + "addressing and routing; the Internet Protocol operates here. The transport layer provides end to end delivery between processes; the "
         + "Transmission Control Protocol offers reliable connection oriented service with acknowledgements, sequence numbers, retransmission and flow "
         + "control, while the User Datagram Protocol offers fast connectionless service without delivery guarantees. The session layer establishes and "
         + "manages dialogues, the presentation layer handles translation, encryption and compression, and the application layer provides protocols such "
         + "as HTTP, FTP, SMTP and DNS directly to user programs. Encapsulation adds a header at each layer as data descends the stack."},

        {"CSE342.14", "Lab 3: IP Addressing and Subnetting", "lab", "1",
         "An Internet Protocol version four address is thirty two bits long and is written as four decimal octets separated by dots. Each address is "
         + "divided into a network portion and a host portion, and the split is indicated by a subnet mask or by slash notation giving the number of "
         + "network bits. Classful addressing defined class A with eight network bits, class B with sixteen and class C with twenty four, but modern "
         + "networks use classless inter domain routing which permits any prefix length. Subnetting borrows bits from the host portion to create "
         + "additional smaller networks. The number of usable hosts in a subnet is two raised to the number of host bits, minus two, because the all "
         + "zeros address identifies the network itself and the all ones address is the directed broadcast. For example a slash twenty six prefix leaves "
         + "six host bits, giving sixty four addresses of which sixty two are usable. Private ranges reserved for internal use are ten dot zero dot zero "
         + "dot zero slash eight, one seven two dot sixteen slash twelve, and one nine two dot one six eight slash sixteen."},

        {"CSE365.11", "Lecture 1: Search Algorithms in Artificial Intelligence", "lecture", "1",
         "Many artificial intelligence problems are solved by searching a state space. A search problem is defined by an initial state, a set of actions, "
         + "a transition model, a goal test and a path cost function. Uninformed search strategies use no domain knowledge. Breadth first search expands "
         + "the shallowest unexpanded node using a first in first out queue and is complete and optimal when all step costs are equal, but its memory "
         + "requirement grows exponentially. Depth first search expands the deepest node using a stack and uses far less memory, but it is neither complete "
         + "on infinite branches nor optimal. Uniform cost search expands the node of lowest path cost and is optimal for non negative costs. Informed or "
         + "heuristic search uses an evaluation function to guide expansion. Greedy best first search expands the node that appears closest to the goal "
         + "according to a heuristic h of n. A star search expands the node minimising f of n equals g of n plus h of n, where g is the cost so far, and it "
         + "is optimal provided the heuristic is admissible, meaning it never overestimates the true remaining cost, and consistent."},

        {"CSE365.11", "Notes: Machine Learning Basics", "notes", "0",
         "Machine learning builds programs that improve their performance on a task through experience rather than through explicit instruction. In "
         + "supervised learning the algorithm is given labelled training examples consisting of input feature vectors paired with the correct output, and "
         + "it learns a mapping that generalises to unseen inputs; classification predicts a discrete class while regression predicts a continuous value. "
         + "In unsupervised learning the data carries no labels and the algorithm discovers structure by itself, for example by clustering similar points "
         + "using the k means algorithm or by reducing dimensionality using principal component analysis. In reinforcement learning an agent takes actions "
         + "in an environment and learns a policy from scalar reward signals. Overfitting occurs when a model memorises noise in the training data and "
         + "therefore performs well on training examples but poorly on new data; underfitting occurs when the model is too simple to capture the underlying "
         + "pattern. Data is usually split into training, validation and test sets, and cross validation gives a more reliable estimate of generalisation."},

        {"ETE282.15", "Lab 1: Amplitude Modulation", "lab", "1",
         "Modulation varies a property of a high frequency carrier wave in proportion to a low frequency message signal so that the message can be "
         + "transmitted efficiently over a channel and radiated by an antenna of practical size. In amplitude modulation the instantaneous amplitude of the "
         + "carrier is varied in proportion to the amplitude of the modulating signal while the carrier frequency and phase remain constant. The modulation "
         + "index, denoted m, equals the peak amplitude of the message divided by the peak amplitude of the carrier. When m is less than one the signal is "
         + "under modulated, when m equals one it is fully modulated, and when m is greater than one over modulation occurs and envelope distortion makes "
         + "recovery of the message impossible. An amplitude modulated wave contains the carrier plus an upper and a lower sideband, so its total bandwidth "
         + "is twice the highest message frequency. Demodulation of an amplitude modulated signal is commonly performed by an envelope detector consisting "
         + "of a diode followed by a resistor capacitor low pass filter."}
    };

    /** name | email | password | role */
    public static final String[][] ACCOUNTS = {
        {"Radiathun Tasnia", "teacher@seu.edu.bd", "teacher123", "teacher"},
        {"Jihad Mahamud", "student@seu.edu.bd", "student123", "student"}
    };
}
